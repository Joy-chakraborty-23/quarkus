package io.quarkus.resteasy.common.deployment;

import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.AnnotationValue.Kind;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.jboss.resteasy.core.MediaTypeMap;
import org.jboss.resteasy.microprofile.config.FilterConfigSource;
import org.jboss.resteasy.microprofile.config.ServletConfigSource;
import org.jboss.resteasy.microprofile.config.ServletContextConfigSource;
import org.jboss.resteasy.plugins.interceptors.AcceptEncodingGZIPFilter;
import org.jboss.resteasy.plugins.interceptors.GZIPDecodingInterceptor;
import org.jboss.resteasy.plugins.interceptors.GZIPEncodingInterceptor;
import org.jboss.resteasy.plugins.providers.StringTextStar;
import org.jboss.resteasy.plugins.providers.sse.SseConstants;
import org.jboss.resteasy.spi.InjectorFactory;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.SystemPropertyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.util.ServiceUtil;
import io.quarkus.resteasy.common.runtime.ResteasyCommonConfig;
import io.quarkus.resteasy.common.runtime.ResteasyInjectorFactoryRecorder;
import io.quarkus.resteasy.common.runtime.config.ResteasyConfigBuilder;
import io.quarkus.resteasy.common.runtime.providers.ServerFormUrlEncodedProvider;
import io.quarkus.resteasy.common.spi.ResteasyConfigBuildItem;
import io.quarkus.resteasy.common.spi.ResteasyDotNames;
import io.quarkus.resteasy.common.spi.ResteasyJaxrsProviderBuildItem;
import io.quarkus.runtime.RuntimeValue;

public class ResteasyCommonProcessor {

    private static final Logger LOGGER = Logger.getLogger(ResteasyCommonProcessor.class.getName());

    private static final ProviderDiscoverer[] PROVIDER_DISCOVERERS = {
            new ProviderDiscoverer(ResteasyDotNames.GET, false, true),
            new ProviderDiscoverer(ResteasyDotNames.HEAD, false, false),
            new ProviderDiscoverer(ResteasyDotNames.DELETE, true, false),
            new ProviderDiscoverer(ResteasyDotNames.OPTIONS, false, true),
            new ProviderDiscoverer(ResteasyDotNames.PATCH, true, false),
            new ProviderDiscoverer(ResteasyDotNames.POST, true, true),
            new ProviderDiscoverer(ResteasyDotNames.PUT, true, false)
    };

    private static final DotName QUARKUS_OBJECT_MAPPER_CONTEXT_RESOLVER = DotName
            .createSimple("io.quarkus.resteasy.common.runtime.jackson.QuarkusObjectMapperContextResolver");
    private static final DotName OBJECT_MAPPER = DotName.createSimple("com.fasterxml.jackson.databind.ObjectMapper");
    private static final DotName QUARKUS_JACKSON_SERIALIZER = DotName
            .createSimple("io.quarkus.resteasy.common.runtime.jackson.QuarkusJacksonSerializer");

    private static final DotName QUARKUS_JSONB_CONTEXT_RESOLVER = DotName
            .createSimple("io.quarkus.resteasy.common.runtime.jsonb.QuarkusJsonbContextResolver");
    private static final DotName JSONB = DotName.createSimple("jakarta.json.bind.Jsonb");
    private static final DotName QUARKUS_JSONB_SERIALIZER = DotName
            .createSimple("io.quarkus.resteasy.common.runtime.jsonb.QuarkusJsonbSerializer");

    private static final String APPLICATION_HAL_JSON = "application/hal+json";

    private static final String[] WILDCARD_MEDIA_TYPE_ARRAY = { MediaType.WILDCARD };

    private ResteasyCommonConfig resteasyCommonConfig;

    @BuildStep
    void addStaticInitConfigSourceProvider(
            Capabilities capabilities,
            BuildProducer<StaticInitConfigBuilderBuildItem> staticInitConfigBuilder,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {

        if (!capabilities.isCapabilityWithPrefixPresent(Capability.SERVLET)) {
            return;
        }

        staticInitConfigBuilder.produce(new StaticInitConfigBuilderBuildItem(ResteasyConfigBuilder.class));

        reflectiveClass.produce(ReflectiveClassBuildItem.builder(ServletConfigSource.class,
                ServletContextConfigSource.class,
                FilterConfigSource.class)
                .reason(getClass().getName())
                .build());
    }

    @BuildStep
    ResteasyConfigBuildItem resteasyConfig(ResteasyJsonConfig resteasyJsonConfig, Capabilities capabilities) {
        return new ResteasyConfigBuildItem(resteasyJsonConfig.jsonDefault() &&
        // RESTEASY_JACKSON or RESTEASY_JACKSON_CLIENT
                (capabilities.isCapabilityWithPrefixPresent(Capability.RESTEASY_JSON_JACKSON)
                        // RESTEASY_JSONB or RESTEASY_JSONB_CLIENT
                        || capabilities.isCapabilityWithPrefixPresent(Capability.RESTEASY_JSON_JSONB)));
    }

    @BuildStep
    void disableDefaultExceptionMapper(BuildProducer<SystemPropertyBuildItem> systemProperties) {
        // RESTEasy 6.2 introduced a default exception mapper (as per JAX-RS 3.1 spec)
        // but we want to keep Quarkus consistent and have Vert.x handle the exceptions
        // as we have a better error handling in our Vert.x error handler.
        systemProperties.produce(new SystemPropertyBuildItem("dev.resteasy.exception.mapper", "false"));
    }

    @BuildStep
    void setupRestEasyManualProviders(BuildProducer<ResteasyJaxrsProviderBuildItem> providers) {
        // this one is added manually in RESTEasy's ResteasyDeploymentImpl
        // https://github.com/quarkusio/quarkus/issues/13667
        providers.produce(new ResteasyJaxrsProviderBuildItem(ServerFormUrlEncodedProvider.class.getName()));
    }

    @BuildStep
    void setupGzipProviders(BuildProducer<ResteasyJaxrsProviderBuildItem> providers) {
        // If GZIP support is enabled, enable it
        if (resteasyCommonConfig.gzip().enabled()) {
            providers.produce(new ResteasyJaxrsProviderBuildItem(AcceptEncodingGZIPFilter.class.getName()));
            providers.produce(new ResteasyJaxrsProviderBuildItem(GZIPDecodingInterceptor.class.getName()));
            providers.produce(new ResteasyJaxrsProviderBuildItem(GZIPEncodingInterceptor.class.getName()));
        }
    }

    @Record(STATIC_INIT)
    @Consume(BeanContainerBuildItem.class)
    @BuildStep
    ResteasyInjectionReadyBuildItem setupResteasyInjection(
            ResteasyInjectorFactoryRecorder recorder) {
        RuntimeValue<InjectorFactory> injectorFactory = recorder.setup();
        return new ResteasyInjectionReadyBuildItem(injectorFactory);
    }

    @BuildStep
    JaxrsProvidersToRegisterBuildItem setupProviders(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            CombinedIndexBuildItem indexBuildItem,
            BeanArchiveIndexBuildItem beanArchiveIndexBuildItem,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans,
            List<ResteasyJaxrsProviderBuildItem> contributedProviderBuildItems,
            List<RestClientBuildItem> restClients,
            ResteasyConfigBuildItem resteasyConfig,
            Capabilities capabilities) throws Exception {

        Set<String> availableProviders = new HashSet<>(ServiceUtil.classNamesNamedIn(getClass().getClassLoader(),
                "META-INF/services/" + Providers.class.getName()));

        MediaTypeMap<String> categorizedReaders = new MediaTypeMap<>();
        MediaTypeMap<String> categorizedWriters = new MediaTypeMap<>();
        MediaTypeMap<String> categorizedContextResolvers = new MediaTypeMap<>();
        Set<String> otherProviders = new HashSet<>();

        categorizeProviders(availableProviders, categorizedReaders, categorizedWriters, categorizedContextResolvers,
                otherProviders);

        if (!capabilities.isPresent(Capability.VERTX)
                && !capabilities.isCapabilityWithPrefixPresent(Capability.RESTEASY_JSON)) {

            boolean needJsonSupport = restJsonSupportNeeded(indexBuildItem, ResteasyDotNames.CONSUMES)
                    || restJsonSupportNeeded(indexBuildItem, ResteasyDotNames.PRODUCES)
                    || restJsonSupportNeeded(indexBuildItem, ResteasyDotNames.RESTEASY_SSE_ELEMENT_TYPE)
                    || restJsonSupportNeeded(indexBuildItem, ResteasyDotNames.RESTEASY_PART_TYPE)
                    || restJsonSupportNeededForHalCapability(capabilities, indexBuildItem);
            if (needJsonSupport) {
                LOGGER.warn(
                        "Quarkus detected the need of REST JSON support but you have not provided the necessary JSON " +
                                "extension for this. You can visit https://quarkus.io/guides/rest-json for more " +
                                "information on how to set one.");
            }
        }
        if (!capabilities.isPresent(Capability.RESTEASY_MUTINY)) {
            String needsMutinyClasses = mutinySupportNeeded(indexBuildItem);
            if (needsMutinyClasses != null) {
                LOGGER.warn(
                        "Quarkus detected the need for Mutiny reactive programming support, however the quarkus-resteasy-mutiny extension "
                                + "was not present. Reactive REST endpoints in your application that return Uni or Multi " +
                                "will not function as you expect until you add this extension. Endpoints that need Mutiny are: "
                                + needsMutinyClasses);
            }

        }

        // add the other providers detected
        Set<String> providersToRegister = new HashSet<>(otherProviders);
        // we add a couple of default providers
        providersToRegister.add(StringTextStar.class.getName());
        providersToRegister.addAll(categorizedWriters.getPossible(MediaType.APPLICATION_JSON_TYPE));

        IndexView index = indexBuildItem.getIndex();
        IndexView beansIndex = beanArchiveIndexBuildItem.getIndex();

        // find the providers declared in our services
        boolean useBuiltinProviders = collectDeclaredProviders(restClients, resteasyConfig,
                providersToRegister, categorizedReaders, categorizedWriters, categorizedContextResolvers,
                index, beansIndex);

        Set<String> contributedProviders = new HashSet<>();
        for (ResteasyJaxrsProviderBuildItem contributedProviderBuildItem : contributedProviderBuildItems) {
            // If we use built-in providers, we can ignore duplicate entries
            if (!useBuiltinProviders || !availableProviders.contains(contributedProviderBuildItem.getName())) {
                contributedProviders.add(contributedProviderBuildItem.getName());
            }
        }

        Set<String> annotatedProviders = new HashSet<>();
        for (AnnotationInstance i : indexBuildItem.getIndex().getAnnotations(ResteasyDotNames.PROVIDER)) {
            if (i.target().kind() == AnnotationTarget.Kind.CLASS) {
                String annotatedProvider = i.target().asClass().name().toString();
                // If we use build-in providers, we can ignore duplicate entries
                if (!useBuiltinProviders || !availableProviders.contains(annotatedProvider)) {
                    annotatedProviders.add(annotatedProvider);
                }
            }
        }
        contributedProviders.addAll(annotatedProviders);

        providersToRegister.addAll(contributedProviders);
        if (useBuiltinProviders) {
            // If we use built-in providers, we need to register all available providers
            for (String availableProvider : availableProviders) {
                reflectiveClass.produce(ReflectiveClassBuildItem.builder(availableProvider).fields().build());
            }
        }

        if (providersToRegister.contains("org.jboss.resteasy.plugins.providers.jsonb.JsonBindingProvider")) {
            // This abstract one is also accessed directly via reflection
            reflectiveClass.produce(
                    ReflectiveClassBuildItem.builder("org.jboss.resteasy.plugins.providers.jsonb.AbstractJsonBindingProvider")
                            .reason(getClass().getName())
                            .methods().fields().build());
        }

        JaxrsProvidersToRegisterBuildItem result = new JaxrsProvidersToRegisterBuildItem(
                providersToRegister, contributedProviders, annotatedProviders, useBuiltinProviders);

        // Providers that are also beans are unremovable
        unremovableBeans.produce(new UnremovableBeanBuildItem(
                b -> result.getProviders().contains(b.getBeanClass().toString())));

        return result;
    }

    private String mutinySupportNeeded(CombinedIndexBuildItem indexBuildItem) {
        Set<DotName> mutinyTypes = new HashSet<>(Arrays.asList(DotName.createSimple("io.smallrye.mutiny.Uni"),
                DotName.createSimple("io.smallrye.mutiny.Multi"),
                DotName.createSimple("io.smallrye.mutiny.GroupedMulti")));
        List<MethodInfo> methods = new ArrayList<>();
        for (DotName annotation : ResteasyDotNames.JAXRS_METHOD_ANNOTATIONS) {
            for (AnnotationInstance instance : indexBuildItem.getIndex().getAnnotations(annotation)) {
                MethodInfo methodInfo = instance.target().asMethod();
                Type type = methodInfo.returnType();
                if (mutinyTypes.contains(type.name())) {
                    methods.add(methodInfo);
                }
            }
        }
        if (methods.isEmpty()) {
            return null;
        }
        return methods.stream().map(new Function<MethodInfo, String>() {
            @Override
            public String apply(MethodInfo methodInfo) {
                return methodInfo.declaringClass().toString() + "{" + methodInfo.toString() + "}";
            }
        }).collect(Collectors.joining(", "));

    }

    @BuildStep
    void registerJsonContextResolvers(CombinedIndexBuildItem combinedIndexBuildItem,
            Capabilities capabilities,
            ResteasyJsonConfig resteasyJsonConfig,
            BuildProducer<ResteasyJaxrsProviderBuildItem> jaxrsProvider,
            BuildProducer<AdditionalBeanBuildItem> additionalBean,
            BuildProducer<UnremovableBeanBuildItem> unremovable) {

        if (capabilities.isCapabilityWithPrefixPresent(Capability.RESTEASY_JSON_JACKSON)) {
            registerJsonContextResolver(OBJECT_MAPPER, QUARKUS_OBJECT_MAPPER_CONTEXT_RESOLVER, combinedIndexBuildItem,
                    jaxrsProvider, additionalBean, unremovable);
            if (resteasyJsonConfig.jsonDefault()) {
                jaxrsProvider.produce(new ResteasyJaxrsProviderBuildItem(QUARKUS_JACKSON_SERIALIZER.toString()));
            }
        }

        if (capabilities.isCapabilityWithPrefixPresent(Capability.RESTEASY_JSON_JSONB)) {
            registerJsonContextResolver(JSONB, QUARKUS_JSONB_CONTEXT_RESOLVER, combinedIndexBuildItem, jaxrsProvider,
                    additionalBean, unremovable);
            if (resteasyJsonConfig.jsonDefault()) {
                jaxrsProvider.produce(new ResteasyJaxrsProviderBuildItem(QUARKUS_JSONB_SERIALIZER.toString()));
            }
        }
    }

    @BuildStep
    void registerNativeImageResources(BuildProducer<ServiceProviderBuildItem> serviceProvider) {
        serviceProvider.produce(ServiceProviderBuildItem
                .allProvidersFromClassPath(org.jboss.resteasy.spi.config.ConfigurationFactory.class.getName()));
    }

    /**
     * ResourceCleaner contains java.lang.ref.Cleaner references which need to get
     * runtime initialized.
     */
    @BuildStep
    public RuntimeInitializedClassBuildItem runtimeInitResourceCleaner() {
        return new RuntimeInitializedClassBuildItem(
                "org.jboss.resteasy.spi.ResourceCleaner");
    }

    private void registerJsonContextResolver(
            DotName jsonImplementation,
            DotName jsonContextResolver,
            CombinedIndexBuildItem combinedIndexBuildItem,
            BuildProducer<ResteasyJaxrsProviderBuildItem> jaxrsProvider,
            BuildProducer<AdditionalBeanBuildItem> additionalBean,
            BuildProducer<UnremovableBeanBuildItem> unremovable) {

        IndexView index = combinedIndexBuildItem.getIndex();

        jaxrsProvider.produce(new ResteasyJaxrsProviderBuildItem(jsonContextResolver.toString()));

        // this needs to be registered manually since the runtime module is not indexed by Jandex
        additionalBean.produce(AdditionalBeanBuildItem.unremovableOf(jsonContextResolver.toString()));
        Set<String> userSuppliedProducers = getUserSuppliedJsonProducerBeans(index, jsonImplementation);
        if (!userSuppliedProducers.isEmpty()) {
            unremovable.produce(
                    new UnremovableBeanBuildItem(new UnremovableBeanBuildItem.BeanClassNamesExclusion(userSuppliedProducers)));
        }
    }

    /*
     * We need to find all the user supplied producers and mark them as unremovable since there are no actual injection points
     * for ObjectMapper/Jsonb.
     */
    private Set<String> getUserSuppliedJsonProducerBeans(IndexView index, DotName jsonImplementation) {
        Set<String> result = new HashSet<>();
        for (AnnotationInstance annotation : index.getAnnotations(DotNames.PRODUCES)) {
            if (annotation.target().kind() != AnnotationTarget.Kind.METHOD) {
                continue;
            }
            if (jsonImplementation.equals(annotation.target().asMethod().returnType().name())) {
                result.add(annotation.target().asMethod().declaringClass().name().toString());
            }
        }
        return result;
    }

    private boolean restJsonSupportNeededForHalCapability(Capabilities capabilities, CombinedIndexBuildItem indexBuildItem) {
        return capabilities.isPresent(Capability.HAL)
                && isMediaTypeFoundInAnnotation(indexBuildItem, ResteasyDotNames.PRODUCES, APPLICATION_HAL_JSON);
    }

    private boolean restJsonSupportNeeded(CombinedIndexBuildItem indexBuildItem, DotName mediaTypeAnnotation) {
        return isMediaTypeFoundInAnnotation(indexBuildItem, mediaTypeAnnotation,
                MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON_PATCH_JSON);
    }

    private boolean isMediaTypeFoundInAnnotation(CombinedIndexBuildItem indexBuildItem, DotName mediaTypeAnnotation,
            String... mediaTypes) {
        for (AnnotationInstance annotationInstance : indexBuildItem.getIndex().getAnnotations(mediaTypeAnnotation)) {
            final AnnotationValue annotationValue = annotationInstance.value();
            if (annotationValue == null) {
                continue;
            }

            List<String> foundMediaTypes = Collections.emptyList();
            if (annotationValue.kind() == Kind.ARRAY) {
                foundMediaTypes = Arrays.asList(annotationValue.asStringArray());
            } else if (annotationValue.kind() == Kind.STRING) {
                foundMediaTypes = Collections.singletonList(annotationValue.asString());
            }

            for (int i = 0; i < mediaTypes.length; i++) {
                if (foundMediaTypes.contains(mediaTypes[i])) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void categorizeProviders(Set<String> availableProviders, MediaTypeMap<String> categorizedReaders,
            MediaTypeMap<String> categorizedWriters, MediaTypeMap<String> categorizedContextResolvers,
            Set<String> otherProviders) {
        for (String availableProvider : availableProviders) {
            try {
                Class<?> providerClass = Class.forName(availableProvider, false,
                        Thread.currentThread().getContextClassLoader());
                if (MessageBodyReader.class.isAssignableFrom(providerClass)
                        || MessageBodyWriter.class.isAssignableFrom(providerClass)) {
                    if (MessageBodyReader.class.isAssignableFrom(providerClass)) {
                        Consumes consumes = providerClass.getAnnotation(Consumes.class);
                        if (consumes != null) {
                            for (String consumesMediaType : consumes.value()) {
                                categorizedReaders.add(MediaType.valueOf(consumesMediaType), providerClass.getName());
                            }
                        } else {
                            categorizedReaders.add(MediaType.WILDCARD_TYPE, providerClass.getName());
                        }
                    }
                    if (MessageBodyWriter.class.isAssignableFrom(providerClass)) {
                        Produces produces = providerClass.getAnnotation(Produces.class);
                        if (produces != null) {
                            for (String producesMediaType : produces.value()) {
                                categorizedWriters.add(MediaType.valueOf(producesMediaType), providerClass.getName());
                            }
                        } else {
                            categorizedWriters.add(MediaType.WILDCARD_TYPE, providerClass.getName());
                        }
                    }
                } else if (ContextResolver.class.isAssignableFrom(providerClass)) {
                    Produces produces = providerClass.getAnnotation(Produces.class);
                    if (produces != null) {
                        for (String producesMediaType : produces.value()) {
                            categorizedContextResolvers.add(MediaType.valueOf(producesMediaType),
                                    providerClass.getName());
                        }
                    } else {
                        categorizedContextResolvers.add(MediaType.WILDCARD_TYPE, providerClass.getName());
                    }
                } else {
                    otherProviders.add(providerClass.getName());
                }
            } catch (ClassNotFoundException e) {
                // Ignore
            }
        }
    }

    private static boolean collectDeclaredProviders(List<RestClientBuildItem> restClients,
            ResteasyConfigBuildItem resteasyConfig,
            Set<String> providersToRegister,
            MediaTypeMap<String> categorizedReaders, MediaTypeMap<String> categorizedWriters,
            MediaTypeMap<String> categorizedContextResolvers,
            IndexView... indexes) {
        Set<String> restClientNames = restClients.stream()
                .map(RestClientBuildItem::getInterfaceName)
                .collect(Collectors.toSet());

        for (IndexView index : indexes) {
            for (ProviderDiscoverer providerDiscoverer : PROVIDER_DISCOVERERS) {
                Collection<AnnotationInstance> getMethods = index.getAnnotations(providerDiscoverer.getMethodAnnotation());
                for (AnnotationInstance getMethod : getMethods) {
                    MethodInfo methodTarget = getMethod.target().asMethod();
                    boolean isRestClient = restClientNames.contains(methodTarget.declaringClass().name().toString());

                    if (isRestClient) {
                        // when dealing with a REST client, we need to collect @Consumes as writers and @Produces as readers
                        if (collectDeclaredProvidersForMethodAndMediaTypeAnnotation(providersToRegister, categorizedWriters,
                                methodTarget, ResteasyDotNames.CONSUMES, providerDiscoverer.noConsumesDefaultsToAll(),
                                resteasyConfig.isJsonDefault())) {
                            return true;
                        }
                        if (collectDeclaredProvidersForMethodAndMediaTypeAnnotation(providersToRegister, categorizedReaders,
                                methodTarget, ResteasyDotNames.PRODUCES, providerDiscoverer.noProducesDefaultsToAll(),
                                resteasyConfig.isJsonDefault())) {
                            return true;
                        }
                    } else {
                        // for JAX-RS resources, we do the opposite
                        if (collectDeclaredProvidersForMethodAndMediaTypeAnnotation(providersToRegister, categorizedReaders,
                                methodTarget, ResteasyDotNames.CONSUMES, providerDiscoverer.noConsumesDefaultsToAll(),
                                resteasyConfig.isJsonDefault())) {
                            return true;
                        }
                        if (collectDeclaredProvidersForMethodAndMediaTypeAnnotation(providersToRegister, categorizedWriters,
                                methodTarget, ResteasyDotNames.PRODUCES, providerDiscoverer.noProducesDefaultsToAll(),
                                resteasyConfig.isJsonDefault())) {
                            return true;
                        }
                    }

                    if (collectDeclaredProvidersForMethodAndMediaTypeAnnotation(providersToRegister,
                            categorizedContextResolvers, methodTarget, ResteasyDotNames.CONSUMES,
                            providerDiscoverer.noConsumesDefaultsToAll(), resteasyConfig.isJsonDefault())) {
                        return true;
                    }
                    if (collectDeclaredProvidersForMethodAndMediaTypeAnnotation(providersToRegister,
                            categorizedContextResolvers, methodTarget, ResteasyDotNames.PRODUCES,
                            providerDiscoverer.noProducesDefaultsToAll(), resteasyConfig.isJsonDefault())) {
                        return true;
                    }
                }
            }

            // handle @PartType: we don't know if it's used for writing or reading, so we register both
            for (AnnotationInstance partTypeAnnotation : index.getAnnotations(ResteasyDotNames.RESTEASY_PART_TYPE)) {
                try {
                    MediaType partTypeMediaType = MediaType.valueOf(partTypeAnnotation.value().asString());
                    providersToRegister.addAll(categorizedReaders.getPossible(partTypeMediaType));
                    providersToRegister.addAll(categorizedWriters.getPossible(partTypeMediaType));
                } catch (IllegalArgumentException e) {
                    // Let's not throw an error, there's a good chance RESTEasy will do it for us
                    // and if not, this might be valid.
                }
            }
        }
        return false;
    }

    private static boolean collectDeclaredProvidersForMethodAndMediaTypeAnnotation(Set<String> providersToRegister,
            MediaTypeMap<String> categorizedProviders, MethodInfo methodTarget, DotName mediaTypeAnnotation,
            boolean includeDefaults, boolean jsonDefault) {
        AnnotationInstance mediaTypeMethodAnnotationInstance = methodTarget.annotation(mediaTypeAnnotation);
        if (mediaTypeMethodAnnotationInstance == null) {
            // no media types defined on the method, let's consider the class annotations
            AnnotationInstance mediaTypeClassAnnotationInstance = methodTarget.declaringClass()
                    .declaredAnnotation(mediaTypeAnnotation);
            if (mediaTypeClassAnnotationInstance != null) {
                AnnotationValue mediaTypeClassValue = mediaTypeClassAnnotationInstance.value();
                if ((mediaTypeClassValue != null)
                        && collectDeclaredProvidersForMediaTypeAnnotationInstance(providersToRegister, categorizedProviders,
                                mediaTypeClassValue.asStringArray(), methodTarget)) {
                    return true;
                }
                return false;
            }
            // we couldn't find any annotations neither on the method nor on the class, stick to the default
            if (!includeDefaults) {
                return false;
            }
            if (jsonDefault) {
                collectDeclaredProvidersForMediaTypeAnnotationInstance(providersToRegister, categorizedProviders,
                        new String[] { MediaType.APPLICATION_JSON }, methodTarget);
                return false;
            } else {
                return true;
            }
        }
        String[] mediaTypes = WILDCARD_MEDIA_TYPE_ARRAY;
        if (mediaTypeMethodAnnotationInstance.value() != null) {
            mediaTypes = mediaTypeMethodAnnotationInstance.value().asStringArray();
        }
        if (collectDeclaredProvidersForMediaTypeAnnotationInstance(providersToRegister, categorizedProviders,
                mediaTypes, methodTarget)) {
            return true;
        }

        return false;
    }

    private static boolean collectDeclaredProvidersForMediaTypeAnnotationInstance(Set<String> providersToRegister,
            MediaTypeMap<String> categorizedProviders,
            String[] mediaTypes,
            MethodInfo targetMethod) {
        for (String media : mediaTypes) {
            MediaType mediaType = MediaType.valueOf(media);
            if (MediaType.WILDCARD_TYPE.equals(mediaType)) {
                // exit early if we have the wildcard type
                return true;
            }
            providersToRegister.addAll(categorizedProviders.getPossible(mediaType));
            // additionally add any "inferred" providers based on the media type
            providersToRegister.addAll(collectInferredProviders(mediaType, categorizedProviders, targetMethod));
        }
        return false;
    }

    /**
     * Returns a collection of providers that are "inferred" based on certain rules applied to the passed
     * {@code mediaType}. Returns an empty collection if no providers were inferred.
     *
     * @param mediaType The MediaType to process
     * @param categorizedProviders Available providers that are categorized based on their media type. This map
     *        will be used to find possible providers that can be used for the passed
     *        {@code mediaType}
     * @return
     */
    private static Collection<String> collectInferredProviders(final MediaType mediaType,
            final MediaTypeMap<String> categorizedProviders, final MethodInfo targetMethod) {

        // for SERVER_SENT_EVENTS media type, we do certain things:
        // - check if the @SseElementType (RestEasy) specific annotation is specified on the target.
        //   if it is, then include a provider which can handle that element type.
        // - if no @SseElementType is present, check if the media type has the "element-type" parameter
        //   and if it does then include the provider which can handle that element-type
        // - if neither of the above specifies an element-type then we fall back to including text/plain
        //   provider as a default
        if (matches(MediaType.SERVER_SENT_EVENTS_TYPE, mediaType)) {
            final Set<String> additionalProvidersToRegister = new HashSet<>();
            // first check for @SseElementType
            final AnnotationInstance sseElementTypeAnnInst = targetMethod
                    .annotation(ResteasyDotNames.RESTEASY_SSE_ELEMENT_TYPE);
            String elementType = null;
            if (sseElementTypeAnnInst != null) {
                elementType = sseElementTypeAnnInst.value().asString();
            } else if (mediaType.getParameters() != null
                    && mediaType.getParameters().containsKey(SseConstants.SSE_ELEMENT_MEDIA_TYPE)) {
                // fallback on the MediaType parameter
                elementType = mediaType.getParameters().get(SseConstants.SSE_ELEMENT_MEDIA_TYPE);
            }
            if (elementType != null) {
                additionalProvidersToRegister.addAll(categorizedProviders.getPossible(MediaType.valueOf(elementType)));
            } else {
                // add text/plain provider as a fallback default for SSE media-type
                additionalProvidersToRegister.addAll(categorizedProviders.getPossible(MediaType.TEXT_PLAIN_TYPE));
            }
            return additionalProvidersToRegister;
        }
        return Collections.emptySet();
    }

    /**
     * Compares the {@link MediaType#getType() type} and the {@link MediaType#getSubtype() subtype} to see if they are
     * equal (case-insensitive). If they are equal, then this method returns {@code true}, else returns {@code false}.
     * Unlike the {@link MediaType#equals(Object)}, this method doesn't take into account the {@link MediaType#getParameters()
     * parameters} during the equality check
     *
     * @param m1 one of the MediaType
     * @param m2 the other MediaType
     * @return
     */
    private static boolean matches(final MediaType m1, final MediaType m2) {
        if (m1 == null || m2 == null) {
            return false;
        }
        if (m1.getType() == null || m1.getSubtype() == null) {
            return false;
        }
        return m1.getType().equalsIgnoreCase(m2.getType()) && m1.getSubtype().equalsIgnoreCase(m2.getSubtype());
    }

    private static class ProviderDiscoverer {

        private final DotName methodAnnotation;

        private final boolean noConsumesDefaultsToAll;

        private final boolean noProducesDefaultsToAll;

        private ProviderDiscoverer(DotName methodAnnotation, boolean noConsumesDefaultsToAll,
                boolean noProducesDefaultsToAll) {
            this.methodAnnotation = methodAnnotation;
            this.noConsumesDefaultsToAll = noConsumesDefaultsToAll;
            this.noProducesDefaultsToAll = noProducesDefaultsToAll;
        }

        public DotName getMethodAnnotation() {
            return methodAnnotation;
        }

        public boolean noConsumesDefaultsToAll() {
            return noConsumesDefaultsToAll;
        }

        public boolean noProducesDefaultsToAll() {
            return noProducesDefaultsToAll;
        }
    }
}
