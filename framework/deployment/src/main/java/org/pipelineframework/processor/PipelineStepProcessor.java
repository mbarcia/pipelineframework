/*
 * Copyright (c) 2023-2025 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.processor;

import com.squareup.javapoet.*;
import io.quarkus.arc.Unremovable;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.pipelineframework.annotation.PipelineStep;
import org.pipelineframework.grpc.GrpcReactiveServiceAdapter;
import org.pipelineframework.plugin.runtime.AdapterInvoker;
import org.pipelineframework.plugin.runtime.PluginEngine;
import org.pipelineframework.step.StepOneToMany;
import org.pipelineframework.step.StepOneToOne;

/**
 * Java annotation processor that generates both gRPC client and server step implementations
 * based on @PipelineStep annotated service classes.
 */
@SuppressWarnings("unused")
@SupportedAnnotationTypes("org.pipelineframework.annotation.PipelineStep")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class PipelineStepProcessor extends AbstractProcessor {

    /**
     * Suffix to append to generated client step classes.
     */
    public static final String CLIENT_STEP_SUFFIX = "ClientStep";

    /**
     * Suffix to append to generated gRPC service classes.
     */
    public static final String GRPC_SERVICE_SUFFIX = "GrpcService";
    private static final String PIPELINE_PACKAGE_SUFFIX = ".pipeline";

    /**
     * Suffix to append to generated REST resource classes.
     */
    public static final String REST_RESOURCE_SUFFIX = "Resource";

    /**
     * Default constructor for PipelineStepProcessor.
     */
    public PipelineStepProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
    }

    /**
     * Processes elements annotated with {@code @PipelineStep} and generates gRPC server adapters,
     * gRPC client steps, and — when enabled and compatible — REST resource classes.
     * <p>
     * The method validates annotated elements, emits compiler diagnostics for invalid uses or
     * generation failures, and invokes code generation helpers for each discovered service class.
     *
     * @param annotations the set of annotation types requested to be processed for this round
     * @param roundEnv    environment that provides access to elements annotated with the requested annotations
     * @return {@code true} if this processor handled the provided annotations (processing occurred), {@code false} if there were no annotations to process
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(PipelineStep.class)) {
            if (annotatedElement.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, 
                    "@PipelineStep can only be applied to classes", annotatedElement);
                continue;
            }

            TypeElement serviceClass = (TypeElement) annotatedElement;
            PipelineStep pipelineStep = serviceClass.getAnnotation(PipelineStep.class);

            // Get the annotation mirror to access parameter values
            AnnotationMirror annotationMirror = getAnnotationMirror(serviceClass, PipelineStep.class);

            // Generate gRPC service adapter only if annotation mirror exists and gRPC is enabled
            if (annotationMirror != null && getAnnotationValueAsBoolean(annotationMirror, "grpcEnabled", true)) {
                try {
                    generateGrpcServiceAdapter(serviceClass, pipelineStep);
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Failed to generate step (server) for " + serviceClass.getSimpleName() + ": " + e.getMessage());
                }
            }

            // Generate client step only if annotation mirror exists, service is not local, and gRPC is enabled
            if (annotationMirror != null && !pipelineStep.local() && getAnnotationValueAsBoolean(annotationMirror, "grpcEnabled", true)) {
                try {
                    generateClientStep(serviceClass, pipelineStep);
                } catch (IOException e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Failed to generate step (client) for " + serviceClass.getSimpleName() + ": " + e.getMessage());
                }
            }

            // Generate REST resource if annotation mirror exists and restEnabled is true
            boolean restEnabled = annotationMirror != null ? getAnnotationValueAsBoolean(annotationMirror, "restEnabled", false) : false;

            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Checking REST resource generation for " + serviceClass.getSimpleName() +
                ", restEnabled=" + restEnabled);

            if (restEnabled) {
                // Check if service implements ReactiveService, ReactiveStreamingService, ReactiveStreamingClientService, or ReactiveBidirectionalStreamingService
                boolean isReactiveService = implementsInterface(serviceClass, "org.pipelineframework.service.ReactiveService");
                boolean isReactiveStreamingService = implementsInterface(serviceClass, "org.pipelineframework.service.ReactiveStreamingService");
                boolean isReactiveStreamingClientService = implementsInterface(serviceClass, "org.pipelineframework.service.ReactiveStreamingClientService");
                boolean isReactiveBidirectionalStreamingService = implementsInterface(serviceClass, "org.pipelineframework.service.ReactiveBidirectionalStreamingService");

                if (!isReactiveService && !isReactiveStreamingService && !isReactiveStreamingClientService && !isReactiveBidirectionalStreamingService) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Service " + serviceClass.getSimpleName() + " has restEnabled=true but does not implement ReactiveService, ReactiveStreamingService, ReactiveStreamingClientService, or ReactiveBidirectionalStreamingService, skipping REST resource generation");
                } else {
                    String serviceType = isReactiveService ? "ReactiveService" :
                                       isReactiveStreamingService ? "ReactiveStreamingService" :
                                       isReactiveStreamingClientService ? "ReactiveStreamingClientService" :
                                       "ReactiveBidirectionalStreamingService";
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Generating REST resource for " + serviceClass.getSimpleName() +
                        " (type: " + serviceType + ")");
                    try {
                        generateRestResource(serviceClass, pipelineStep, isReactiveService, isReactiveStreamingService, isReactiveStreamingClientService, isReactiveBidirectionalStreamingService);
                    } catch (IOException e) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Failed to generate REST resource for " + serviceClass.getSimpleName() + ": " + e.getMessage());
                    }
                }
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Skipping REST resource generation for " + serviceClass.getSimpleName() +
                    " because restEnabled=" + restEnabled);
            }

            // Generate plugin adapter and service regardless of whether the step is local
            // Plugins are external components that participate in pipelines with typed gRPC endpoints
            try {
                generatePluginAdapter(serviceClass, pipelineStep);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate plugin adapter for " + serviceClass.getSimpleName() + ": " + e.getMessage());
            }

            try {
                generatePluginReactiveService(serviceClass, pipelineStep);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate plugin reactive service for " + serviceClass.getSimpleName() + ": " + e.getMessage());
            }
        }

        return true;
    }

    /**
     * Generates and writes a CDI-enabled pipeline client step class that delegates to a gRPC stub.
     *
     * <p>Reads configuration from the {@link PipelineStep} annotation on the provided service class
     * and generates a Java source file in the same package with a ".pipeline" suffix. The generated
     * class extends ConfigurableStep, implements the appropriate pipeline step interface (OneToOne,
     * OneToMany, ManyToOne, ManyToMany) using gRPC types, and exposes an apply/transform method that
     * calls the configured gRPC client's remoteProcess method. Mapper and gRPC client fields are
     * injected when configured in the annotation.</p>
     *
     * @param serviceClass the annotated service class element to read configuration from and base the generated class on
     * @param pipelineStep the resolved {@code PipelineStep} annotation instance for the service class
     * @throws IOException if writing the generated Java source file fails
     */
    protected void generateClientStep(TypeElement serviceClass, PipelineStep pipelineStep) throws IOException {
        // Get the annotation mirror to extract TypeMirror values
        AnnotationMirror annotationMirror = getAnnotationMirror(serviceClass, PipelineStep.class);
        if (annotationMirror == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, 
                "Could not get annotation mirror for " + serviceClass, serviceClass);
            return;
        }

        // Extract values from the annotation mirror
        TypeMirror grpcStubType = getAnnotationValue(annotationMirror, "grpcStub");
        String grpcClientName = getAnnotationValueAsString(annotationMirror, "grpcClient");
        TypeMirror inboundMapperType = getAnnotationValue(annotationMirror, "inboundMapper");
        TypeMirror outboundMapperType = getAnnotationValue(annotationMirror, "outboundMapper");
        TypeMirror inputGrpcType = getAnnotationValue(annotationMirror, "inputGrpcType");
        TypeMirror outputGrpcType = getAnnotationValue(annotationMirror, "outputGrpcType");
        TypeMirror stepType = getAnnotationValue(annotationMirror, "stepType");

        // Create the package for the generated class
        // Use the same package as the original service but with a ".pipeline" suffix
        String packageName = processingEnv.getElementUtils()
            .getPackageOf(serviceClass).getQualifiedName().toString() + PIPELINE_PACKAGE_SUFFIX;
        
        // Create the simple name for the generated client step
        String serviceClassName = serviceClass.getSimpleName().toString();
        String clientStepClassName = serviceClassName.replace("Service", "") + CLIENT_STEP_SUFFIX;
        
        // Create the class with Dependent annotation for CDI and Unremovable to prevent Quarkus from removing it during build
        TypeSpec.Builder clientStepBuilder = TypeSpec.classBuilder(clientStepClassName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.enterprise.context", "Dependent"))
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get(io.quarkus.arc.Unremovable.class))
                .build());


        // Add necessary imports as field declarations or annotations
        if (grpcStubType != null && !grpcStubType.toString().equals("void")) {
            // Add gRPC client field with @GrpcClient annotation
            FieldSpec grpcClientField = FieldSpec.builder(
                ClassName.get(grpcStubType),
                "grpcClient",
                Modifier.PRIVATE)
                .addAnnotation(AnnotationSpec.builder(GrpcClient.class)
                    .addMember("value", "$S", grpcClientName)
                    .build())
                .build();
            
            clientStepBuilder.addField(grpcClientField);
        }
        
        // Add mapper fields with CDI injection if provided
        if (inboundMapperType != null && !inboundMapperType.toString().equals("void")) {
            FieldSpec inboundMapperField = FieldSpec.builder(
                ClassName.get(inboundMapperType),
                "inboundMapper",
                Modifier.PRIVATE)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.inject", "Inject"))
                    .build())
                .build();
            clientStepBuilder.addField(inboundMapperField);
        }
        
        if (outboundMapperType != null && !outboundMapperType.toString().equals("void")) {
            FieldSpec outboundMapperField = FieldSpec.builder(
                ClassName.get(outboundMapperType),
                "outboundMapper",
                Modifier.PRIVATE)
                .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.inject", "Inject"))
                    .build())
                .build();
            clientStepBuilder.addField(outboundMapperField);
        }
        
        // Add default constructor
        MethodSpec constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .build();
        clientStepBuilder.addMethod(constructor);
        
        // Extend ConfigurableStep and implement the pipeline step interface based on stepType
        // Use gRPC types for the interface, as the client steps work with gRPC types directly
        // The server-side GrpcReactiveServiceAdapter handles gRPC-to-domain conversion
        ClassName configurableStep = ClassName.get("org.pipelineframework.step", "ConfigurableStep");
        clientStepBuilder.superclass(configurableStep);

        ClassName stepInterface;
        if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepOneToOne")) {
            stepInterface = ClassName.get(StepOneToOne.class);
            clientStepBuilder.addSuperinterface(ParameterizedTypeName.get(stepInterface,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, 
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT));
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepOneToMany")) {
            stepInterface = ClassName.get(StepOneToMany.class);
            clientStepBuilder.addSuperinterface(ParameterizedTypeName.get(stepInterface,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, 
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT));
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToOne")) {
            // Handle StepManyToOne specifically
            stepInterface = ClassName.get("org.pipelineframework.step", "StepManyToOne");
            clientStepBuilder.addSuperinterface(ParameterizedTypeName.get(stepInterface,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, 
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT));
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToMany")) {
            // Handle StepManyToMany
            stepInterface = ClassName.get("org.pipelineframework.step", "StepManyToMany");
            clientStepBuilder.addSuperinterface(ParameterizedTypeName.get(stepInterface,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, 
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT));
        } else {
            // Default to OneToOne for simplicity; can extend to support more types
            stepInterface = ClassName.get(StepOneToOne.class);
            clientStepBuilder.addSuperinterface(ParameterizedTypeName.get(stepInterface,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, 
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT));
        }
        
        // Add the apply method implementation based on the step type
        // No mapping needed - client steps work directly with gRPC types
        // Server-side GrpcReactiveServiceAdapter handles gRPC-to-domain conversion
        if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepOneToMany")) {
            // For OneToMany: Input -> Multi<Output> (StepOneToMany interface has applyOneToMany(Input in) method)
            MethodSpec applyMethod = MethodSpec.methodBuilder("applyOneToMany")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Multi.class), 
                    outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT))
                .addParameter(inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, "input")
                .addStatement("return this.grpcClient.remoteProcess(input)")
                .build();
                
            clientStepBuilder.addMethod(applyMethod);
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToOne")) {
            // For ManyToOne: Multi<Input> -> Uni<Output> (ManyToOne interface has applyBatchMulti(Multi<Input> in) method)
            MethodSpec applyMethod = MethodSpec.methodBuilder("applyBatchMulti")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Uni.class), 
                    outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT))
                .addParameter(ParameterizedTypeName.get(ClassName.get(Multi.class), 
                    inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT), "inputs")
                .addStatement("return this.grpcClient.remoteProcess(inputs)")
                .build();
                
            clientStepBuilder.addMethod(applyMethod);
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToMany")) {
            // For ManyToMany: Multi<Input> -> Multi<Output> (ManyToMany interface has applyTransform(Multi<Input> in) method)
            MethodSpec applyMethod = MethodSpec.methodBuilder("applyTransform")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Multi.class), 
                    outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT))
                .addParameter(ParameterizedTypeName.get(ClassName.get(Multi.class), 
                    inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT), "inputs")
                .addStatement("return this.grpcClient.remoteProcess(inputs)")
                .build();
                
            clientStepBuilder.addMethod(applyMethod);
        } else {
            // Default to OneToOne: Input -> Uni<Output> (StepOneToOne interface has applyOneToOne(Input in) method)
            MethodSpec applyMethod = MethodSpec.methodBuilder("applyOneToOne")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Uni.class), 
                    outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT))
                .addParameter(inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, "input")
                .addStatement("return this.grpcClient.remoteProcess(input)")
                .build();
                
            clientStepBuilder.addMethod(applyMethod);
        }
        
        TypeSpec clientStepClass = clientStepBuilder.build();
        
        // Write the generated class
        JavaFile javaFile = JavaFile.builder(packageName, clientStepClass)
            .build();
            
        JavaFileObject builderFile = processingEnv.getFiler()
            .createSourceFile(packageName + "." + clientStepClassName);
        
        try (var writer = builderFile.openWriter()) {
            javaFile.writeTo(writer);
        }
    }

    /**
     * Generates and writes a gRPC service adapter class for the given service annotated with @PipelineStep.
     * <p>
     * The generated class is placed in the original service package with a ".pipeline" suffix, extends an
     * appropriate gRPC base implementation, is annotated for CDI and gRPC wiring, injects the original service
     * and optional mappers/persistence manager, and exposes a remoteProcess method implemented via an inline
     * adapter chosen according to the configured step type.
     *
     * @param serviceClass the TypeElement representing the annotated service class
     * @param pipelineStep the PipelineStep annotation instance for the service (used to read configured values)
     * @throws IOException if writing the generated Java source file fails
     */
    protected void generateGrpcServiceAdapter(TypeElement serviceClass, PipelineStep pipelineStep) throws IOException {
        // Get the annotation mirror to extract TypeMirror values
        AnnotationMirror annotationMirror = getAnnotationMirror(serviceClass, PipelineStep.class);
        if (annotationMirror == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, 
                "Could not get annotation mirror for " + serviceClass, serviceClass);
            return;
        }

        // Extract values from the annotation mirror
        TypeMirror inputType = getAnnotationValue(annotationMirror, "inputType");
        TypeMirror outputType = getAnnotationValue(annotationMirror, "outputType");
        TypeMirror inboundMapperType = getAnnotationValue(annotationMirror, "inboundMapper");
        TypeMirror outboundMapperType = getAnnotationValue(annotationMirror, "outboundMapper");
        TypeMirror inputGrpcType = getAnnotationValue(annotationMirror, "inputGrpcType");
        TypeMirror outputGrpcType = getAnnotationValue(annotationMirror, "outputGrpcType");
        TypeMirror stepType = getAnnotationValue(annotationMirror, "stepType");
        TypeMirror grpcImplType = getAnnotationValue(annotationMirror, "grpcImpl");
        String serviceName = serviceClass.getQualifiedName().toString();
        TypeMirror backendType = getAnnotationValue(annotationMirror, "backendType");

        // Use the same package as the original service but with a ".pipeline" suffix
        String fqcn = serviceClass.getQualifiedName().toString();
        String originalPackage = fqcn.substring(0, fqcn.lastIndexOf('.'));
        String pkg = String.format("%s%s", originalPackage, PIPELINE_PACKAGE_SUFFIX);
        String simpleClassName = String.format("%s%s", serviceClass.getSimpleName().toString(), GRPC_SERVICE_SUFFIX);

        // Determine the backend type (default to GrpcReactiveServiceAdapter)
        ClassName backendClassName;
        if (backendType != null) {
            // Convert TypeMirror to ClassName
            String backendTypeStr = backendType.toString();
            // Split the name to package and simple name
            int lastDot = backendTypeStr.lastIndexOf('.');
            if (lastDot > 0) {
                String packageName = backendTypeStr.substring(0, lastDot);
                String simpleName = backendTypeStr.substring(lastDot + 1);
                backendClassName = ClassName.get(packageName, simpleName);
            } else {
                // If no package, just use the simple name
                backendClassName = ClassName.get("", backendTypeStr);
            }
        } else {
            backendClassName = ClassName.get(GrpcReactiveServiceAdapter.class);
        }

        // Determine the appropriate gRPC service base class based on the grpcImpl annotation value
        ClassName grpcBaseClassName;
        if (grpcImplType != null && !grpcImplType.toString().equals("void")) {
            // Use the grpcImpl class as the base class
            String grpcImplTypeStr = grpcImplType.toString();
            int lastDot = grpcImplTypeStr.lastIndexOf('.');
            if (lastDot > 0) {
                String packageName = grpcImplTypeStr.substring(0, lastDot);
                String simpleName = grpcImplTypeStr.substring(lastDot + 1);
                grpcBaseClassName = ClassName.get(packageName, simpleName);
            } else {
                grpcBaseClassName = ClassName.get("", grpcImplTypeStr);
            }
        } else {
            // Fallback to determine the package from available gRPC types
            // Default to the original package with .grpc suffix if no gRPC types available
            String grpcPackage = originalPackage + ".grpc";
            
            // Try to determine the actual gRPC package from inputGrpcType or outputGrpcType
            if (inputGrpcType != null && !inputGrpcType.toString().equals("void")) {
                grpcPackage = extractPackage(inputGrpcType, grpcPackage);
            } else if (outputGrpcType != null && !outputGrpcType.toString().equals("void")) {
                grpcPackage = extractPackage(outputGrpcType, grpcPackage);
            }
            
            // Construct the gRPC service base class using the determined package
            String grpcServiceBaseClass = grpcPackage + "." + 
                serviceClass.getSimpleName().toString().replace("Service", "") + "Grpc." + 
                serviceClass.getSimpleName().toString().replace("Service", "") + "ImplBase";
            int lastDot = grpcServiceBaseClass.lastIndexOf('.');
            if (lastDot > 0) {
                String packageName = grpcServiceBaseClass.substring(0, lastDot);
                String simpleName = grpcServiceBaseClass.substring(lastDot + 1);
                grpcBaseClassName = ClassName.get(packageName, simpleName);
            } else {
                grpcBaseClassName = ClassName.get("", grpcServiceBaseClass);
            }
        }

        // Create the gRPC service class
        TypeSpec.Builder grpcServiceBuilder = TypeSpec.classBuilder(simpleClassName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get(GrpcService.class)).build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.inject", "Singleton")).build())
            .addAnnotation(AnnotationSpec.builder(Unremovable.class).build())
            .superclass(grpcBaseClassName); // Extend the actual gRPC service base class

        // Add field declarations
        if (inboundMapperType != null && !inboundMapperType.toString().equals("void")) {
            FieldSpec inboundMapperField = FieldSpec.builder(
                ClassName.get(inboundMapperType),
                "inboundMapper")
                .addAnnotation(AnnotationSpec.builder(Inject.class).build())
                .build();
            grpcServiceBuilder.addField(inboundMapperField);
        }

        if (outboundMapperType != null && !outboundMapperType.toString().equals("void")) {
            FieldSpec outboundMapperField = FieldSpec.builder(
                ClassName.get(outboundMapperType),
                "outboundMapper")
                .addAnnotation(AnnotationSpec.builder(Inject.class).build())
                .build();
            grpcServiceBuilder.addField(outboundMapperField);
        }

        FieldSpec serviceField = FieldSpec.builder(
            ClassName.get(serviceClass),
            "service")
            .addAnnotation(AnnotationSpec.builder(Inject.class).build())
            .build();
        grpcServiceBuilder.addField(serviceField);

        // Add persistence manager field
        FieldSpec persistenceManagerField = FieldSpec.builder(
                ClassName.get("org.pipelineframework.persistence", "PersistenceManager"),
                "persistenceManager")
            .addAnnotation(AnnotationSpec.builder(Inject.class).build())
            .build();
        grpcServiceBuilder.addField(persistenceManagerField);


        // Get runOnVirtualThreads value to determine if we need to add @RunOnVirtualThread annotation
        boolean runOnVirtualThreads = getAnnotationValueAsBoolean(annotationMirror, "runOnVirtualThreads", false);

        // Determine which gRPC adapter to use based on the step type
        ClassName grpcAdapterClassName;
        if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepOneToMany")) {
            // For OneToMany: unary input -> streaming output
            grpcAdapterClassName = ClassName.get("org.pipelineframework.grpc", "GrpcServiceStreamingAdapter");
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToOne")) {
            // For ManyToOne: streaming input -> unary output
            grpcAdapterClassName = ClassName.get("org.pipelineframework.grpc", "GrpcServiceClientStreamingAdapter");
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToMany")) {
            // For ManyToMany: streaming input -> streaming output (bidirectional)
            grpcAdapterClassName = ClassName.get("org.pipelineframework.grpc", "GrpcServiceBidirectionalStreamingAdapter");
        } else {
            // Default to GrpcReactiveServiceAdapter for OneToOne
            grpcAdapterClassName = ClassName.get("org.pipelineframework.grpc", "GrpcReactiveServiceAdapter");
        }

        // Add the required gRPC service method implementation based on the gRPC service base class
        // For Mutiny gRPC services, all methods return Uni/Multi, not use StreamObserver
        // Following the manual template, the adapter is created inline inside the method
        if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepOneToMany")) {
            // For server streaming (unary input, streaming output) - e.g., ProcessFolderService
            // Creates the adapter inline as an anonymous class inside the method
            createAndAddRemoteProcessMethod(grpcServiceBuilder, grpcAdapterClassName,
                inputGrpcType, outputGrpcType, inputType, outputType, serviceClass,
                inboundMapperType, outboundMapperType, runOnVirtualThreads,
                ClassName.get("io.smallrye.mutiny", "Multi"),
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, "request");
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToOne")) {
            // For client streaming (streaming input, unary output) - e.g., ProcessCsvPaymentsOutputFileService
            createAndAddRemoteProcessMethod(grpcServiceBuilder, grpcAdapterClassName,
                inputGrpcType, outputGrpcType, inputType, outputType, serviceClass,
                inboundMapperType, outboundMapperType, runOnVirtualThreads,
                ClassName.get("io.smallrye.mutiny", "Uni"),
                ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Multi"),
                    inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT), "request");
        } else if (stepType != null && stepType.toString().equals("org.pipelineframework.step.StepManyToMany")) {
            // For bidirectional streaming (streaming input, streaming output)
            createAndAddRemoteProcessMethod(grpcServiceBuilder, grpcAdapterClassName,
                inputGrpcType, outputGrpcType, inputType, outputType, serviceClass,
                inboundMapperType, outboundMapperType, runOnVirtualThreads,
                ClassName.get("io.smallrye.mutiny", "Multi"),
                ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Multi"),
                    inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT), "request");
        } else {
            // Default to unary (unary input, unary output) - e.g., ProcessPaymentStatusGrpcService
            createAndAddRemoteProcessMethod(grpcServiceBuilder, grpcAdapterClassName,
                inputGrpcType, outputGrpcType, inputType, outputType, serviceClass,
                inboundMapperType, outboundMapperType, runOnVirtualThreads,
                ClassName.get("io.smallrye.mutiny", "Uni"),
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, "request");
        }

        TypeSpec grpcServiceClass = grpcServiceBuilder.build();

        // Write the generated class
        JavaFile javaFile = JavaFile.builder(pkg, grpcServiceClass)
            .build();

        JavaFileObject builderFile = processingEnv.getFiler()
            .createSourceFile(pkg + "." + simpleClassName);

        try (var writer = builderFile.openWriter()) {
            javaFile.writeTo(writer);
        }
    }

    /**
     * Resolve the package name for a gRPC type, falling back to the provided package when resolution fails.
     *
     * @param inputGrpcType the TypeMirror representing the gRPC type to inspect
     * @param grpcPackage   the fallback package name to return if the type's package cannot be determined
     * @return              the fully qualified package name of the given type, or `grpcPackage` if it cannot be resolved
     */
    private String extractPackage(TypeMirror inputGrpcType, String grpcPackage) {
        Element element = processingEnv.getTypeUtils().asElement(inputGrpcType);
        if (element != null) {
            PackageElement pkgEl = processingEnv.getElementUtils().getPackageOf(element);
            if (pkgEl != null) {
                return pkgEl.getQualifiedName().toString();
            }
        }
        return grpcPackage;
    }
    
    /**
     * Generate a REST resource class that exposes the annotated reactive service as HTTP endpoints.
     * <p>
     * The generated resource maps request and response DTOs to domain types using configured mappers,
     * delegates processing to the domain service, and registers an exception mapper for runtime errors.
     *
     * @param serviceClass the annotated service class element
     * @param pipelineStep the PipelineStep annotation instance for the service
     * @param isReactiveService true if the service implements ReactiveService (single-request reactive)
     * @param isReactiveStreamingService true if the service implements ReactiveStreamingService (single request -> streaming response)
     * @param isReactiveStreamingClientService true if the service implements ReactiveStreamingClientService (streaming request -> single response)
     * @param isReactiveBidirectionalStreamingService true if the service implements ReactiveBidirectionalStreamingService (streaming request -> streaming response)
     * @throws IOException if writing the generated Java source file fails
     */
    protected void generateRestResource(TypeElement serviceClass, PipelineStep pipelineStep, boolean isReactiveService, boolean isReactiveStreamingService, boolean isReactiveStreamingClientService, boolean isReactiveBidirectionalStreamingService) throws IOException {
        // Get the annotation mirror to extract TypeMirror values
        AnnotationMirror annotationMirror = getAnnotationMirror(serviceClass, PipelineStep.class);
        if (annotationMirror == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, 
                "Could not get annotation mirror for " + serviceClass, serviceClass);
            return;
        }

        // Extract values from the annotation mirror
        TypeMirror inputType = getAnnotationValue(annotationMirror, "inputType");
        TypeMirror outputType = getAnnotationValue(annotationMirror, "outputType");
        TypeMirror inboundMapperType = getAnnotationValue(annotationMirror, "inboundMapper");
        TypeMirror outboundMapperType = getAnnotationValue(annotationMirror, "outboundMapper");
        String path = getAnnotationValueAsString(annotationMirror, "path");
        boolean runOnVirtualThreads = getAnnotationValueAsBoolean(annotationMirror, "runOnVirtualThreads", false);

        // Validate that required mappers are present for REST generation
        if (inboundMapperType == null || inboundMapperType.toString().equals("void") ||
            outboundMapperType == null || outboundMapperType.toString().equals("void")) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                    "REST generation requires both inboundMapper and outboundMapper to be configured for "
                   + serviceClass.getSimpleName(),
                serviceClass);
            return;
        }

        // Use the same package as the original service but with a ".pipeline" suffix (like gRPC services)
        String fqcn = serviceClass.getQualifiedName().toString();
        String originalPackage = fqcn.substring(0, fqcn.lastIndexOf('.'));
        String pkg = originalPackage + PIPELINE_PACKAGE_SUFFIX;
        String serviceClassName = serviceClass.getSimpleName().toString();
        
        // Determine the resource class name - remove "Service" and optionally "Reactive" for cleaner naming
        String baseName = serviceClassName.replace("Service", "");
        // For services with "Reactive" in the name, we might want to remove it for cleaner resource names
        if (baseName.endsWith("Reactive")) {
            baseName = baseName.substring(0, baseName.length() - "Reactive".length());
        }
        String resourceClassName = baseName + REST_RESOURCE_SUFFIX;

        // Create the REST resource class
        TypeSpec.Builder resourceBuilder = TypeSpec.classBuilder(resourceClassName)
            .addModifiers(Modifier.PUBLIC);

        // Add @Path annotation - derive path from service class name or use provided path
        String servicePath = path != null && !path.isEmpty() ? path : deriveResourcePath(serviceClassName);
        resourceBuilder.addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Path"))
            .addMember("value", "$S", servicePath)
            .build());

        // Add service field with @Inject
        FieldSpec serviceField = FieldSpec.builder(
            ClassName.get(serviceClass),
            "domainService")
            .addAnnotation(AnnotationSpec.builder(Inject.class).build())
            .build();
        resourceBuilder.addField(serviceField);

        // Add mapper fields with @Inject if they exist
        String inboundMapperFieldName = "inboundMapper";
        String outboundMapperFieldName = "outboundMapper";
        
        if (!inboundMapperType.toString().equals("void")) {
            // Create field for inbound mapper
            String inboundMapperPackage = inboundMapperType.toString().substring(0, inboundMapperType.toString().lastIndexOf('.'));
            String inboundMapperSimpleName = inboundMapperType.toString().substring(inboundMapperType.toString().lastIndexOf('.') + 1);
            ClassName inboundMapperClassName = ClassName.get(inboundMapperPackage, inboundMapperSimpleName);
            
            // Create proper field name (e.g., CustomerInputMapper -> customerInputMapper)
            inboundMapperFieldName = inboundMapperSimpleName.substring(0, 1).toLowerCase() + inboundMapperSimpleName.substring(1);
            
            FieldSpec inboundMapperField = FieldSpec.builder(
                inboundMapperClassName,
                inboundMapperFieldName)
                .addAnnotation(AnnotationSpec.builder(Inject.class).build())
                .build();
            resourceBuilder.addField(inboundMapperField);
        }

        if (!outboundMapperType.toString().equals("void")) {
            // Create field for outbound mapper
            String outboundMapperPackage = outboundMapperType.toString().substring(0, outboundMapperType.toString().lastIndexOf('.'));
            String outboundMapperSimpleName = outboundMapperType.toString().substring(outboundMapperType.toString().lastIndexOf('.') + 1);
            ClassName outboundMapperClassName = ClassName.get(outboundMapperPackage, outboundMapperSimpleName);
            
            // Create proper field name (e.g., CustomerOutputMapper -> customerOutputMapper)
            outboundMapperFieldName = outboundMapperSimpleName.substring(0, 1).toLowerCase() + outboundMapperSimpleName.substring(1);
            
            FieldSpec outboundMapperField = FieldSpec.builder(
                outboundMapperClassName,
                outboundMapperFieldName)
                .addAnnotation(AnnotationSpec.builder(Inject.class).build())
                .build();
            resourceBuilder.addField(outboundMapperField);
        }

        // Add logger field to the resource class (before process method)
        FieldSpec loggerField = FieldSpec.builder(
                        ClassName.get("org.jboss.logging", "Logger"),
                        "logger")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($L.class)",
                        ClassName.get("org.jboss.logging", "Logger"),
                        resourceClassName)
                .build();
        resourceBuilder.addField(loggerField);

        // Determine input and output DTO types
        TypeName inputDtoClassName;
        TypeName outputDtoClassName;
        
        if (inputType != null) {
            String inputDtoName = getDtoType(inputType);
            String inputDtoPackage = inputDtoName.contains(".") ? inputDtoName.substring(0, inputDtoName.lastIndexOf('.')) : "";
            String inputDtoSimpleName = inputDtoName.contains(".") ? inputDtoName.substring(inputDtoName.lastIndexOf('.') + 1) : inputDtoName;
            inputDtoClassName = inputDtoPackage.isEmpty() ? 
                ClassName.get("", inputDtoSimpleName) : 
                ClassName.get(inputDtoPackage, inputDtoSimpleName);
        } else {
            inputDtoClassName = ClassName.OBJECT;
        }
        
        if (outputType != null) {
            String outputDtoName = getDtoType(outputType);
            String outputDtoPackage = outputDtoName.contains(".") ? outputDtoName.substring(0, outputDtoName.lastIndexOf('.')) : "";
            String outputDtoSimpleName = outputDtoName.contains(".") ? outputDtoName.substring(outputDtoName.lastIndexOf('.') + 1) : outputDtoName;
            outputDtoClassName = outputDtoPackage.isEmpty() ? 
                ClassName.get("", outputDtoSimpleName) : 
                ClassName.get(outputDtoPackage, outputDtoSimpleName);
        } else {
            outputDtoClassName = ClassName.OBJECT;
        }

        // Create the process method based on service type
        MethodSpec processMethod;
        if (isReactiveStreamingService) {
            // For ReactiveStreamingService: input -> Multi<output>
            processMethod = createReactiveStreamingServiceProcessMethod(
                inputDtoClassName, outputDtoClassName,
                inboundMapperFieldName, outboundMapperFieldName, inputType, outputType, runOnVirtualThreads);
        } else if (isReactiveStreamingClientService) {
            // For ReactiveStreamingClientService: Multi<input> -> output
            processMethod = createReactiveStreamingClientServiceProcessMethod(
                inputDtoClassName, outputDtoClassName,
                inboundMapperFieldName, outboundMapperFieldName, inputType, outputType, runOnVirtualThreads);
        } else if (isReactiveBidirectionalStreamingService) {
            // For ReactiveBidirectionalStreamingService: Multi<input> -> Multi<output>
            processMethod = createReactiveBidirectionalStreamingServiceProcessMethod(
                inputDtoClassName, outputDtoClassName,
                inboundMapperFieldName, outboundMapperFieldName, inputType, outputType, runOnVirtualThreads);
        } else {
            // For regular ReactiveService: input -> output
            processMethod = createReactiveServiceProcessMethod(
                inputDtoClassName, outputDtoClassName,
                inboundMapperFieldName, outboundMapperFieldName, inputType, outputType, runOnVirtualThreads);
        }
        
        resourceBuilder.addMethod(processMethod);

        // Add exception mapper method to handle different types of exceptions
        MethodSpec exceptionMapperMethod = MethodSpec.methodBuilder("handleException")
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.jboss.resteasy.reactive.server", "ServerExceptionMapper"))
                .build())
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassName.get("org.jboss.resteasy.reactive", "RestResponse"))
            .addParameter(Exception.class, "ex")
            .beginControlFlow("if (ex instanceof $T)", IllegalArgumentException.class)
                .addStatement("logger.warn(\"Invalid request\", ex)")
                .addStatement("return $T.status($T.Status.BAD_REQUEST, \"Invalid request\")",
                ClassName.get("org.jboss.resteasy.reactive", "RestResponse"),
                ClassName.get("jakarta.ws.rs.core", "Response"))
            .nextControlFlow("else if (ex instanceof $T)", RuntimeException.class)
                .addStatement("logger.error(\"Unexpected error processing request\", ex)")
                .addStatement("return $T.status($T.Status.INTERNAL_SERVER_ERROR, \"An unexpected error occurred\")",
                ClassName.get("org.jboss.resteasy.reactive", "RestResponse"),
                ClassName.get("jakarta.ws.rs.core", "Response"))
            .nextControlFlow("else")
                .addStatement("logger.error(\"Unexpected error processing request\", ex)")
                .addStatement("return $T.status($T.Status.INTERNAL_SERVER_ERROR, \"An unexpected error occurred\")",
                ClassName.get("org.jboss.resteasy.reactive", "RestResponse"),
                ClassName.get("jakarta.ws.rs.core", "Response"))
            .endControlFlow()
            .build();
        
        resourceBuilder.addMethod(exceptionMapperMethod);

        TypeSpec resourceClass = resourceBuilder.build();

        // Write the generated class
        JavaFile javaFile = JavaFile.builder(pkg, resourceClass)
            .build();

        JavaFileObject builderFile = processingEnv.getFiler()
            .createSourceFile(pkg + "." + resourceClassName);

        try (var writer = builderFile.openWriter()) {
            javaFile.writeTo(writer);
        }
        
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, 
            "Successfully generated REST resource: " + pkg + "." + resourceClassName);
    }
    
    /**
     * Create the JAX-RS `process` method for a ReactiveStreamingService that accepts a single input DTO and returns a stream of output DTOs.
     *
     * @param inboundMapperFieldName  field name of the mapper used to convert DTO to domain; may be null if no mapper is configured
     * @param outboundMapperFieldName field name of the mapper used to convert domain to DTO; may be null if no mapper is configured
     * @param inputType               the domain input TypeMirror from the service generics; may be null when unavailable
     * @param outputType              the domain output TypeMirror from the service generics; may be null when unavailable
     * @param runOnVirtualThreads     if true, the generated method will be annotated with `@RunOnVirtualThread`
     * @return                        a MethodSpec representing the generated REST `process` method that returns a `Multi` of output DTOs
     */
    private MethodSpec createReactiveStreamingServiceProcessMethod(
            TypeName inputDtoClassName, TypeName outputDtoClassName,
            String inboundMapperFieldName, String outboundMapperFieldName,
            TypeMirror inputType, TypeMirror outputType, boolean runOnVirtualThreads) {

        TypeName multiOutputDto = ParameterizedTypeName.get(ClassName.get(Multi.class), outputDtoClassName);

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("process")
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "POST"))
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Path"))
                .addMember("value", "$S", "/process")
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.jboss.resteasy.reactive", "RestStreamElementType"))
                .addMember("value", "$S", "application/json")
                .build())
            .addModifiers(Modifier.PUBLIC)
            .returns(multiOutputDto)
            .addParameter(inputDtoClassName, "inputDto");

        // Add the implementation code
        methodBuilder.addStatement("$T inputDomain = $L.fromDto(inputDto)",
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                inboundMapperFieldName != null ? inboundMapperFieldName : "/* mapper missing */");

        // Return the stream, allowing errors to propagate to the exception mapper
        methodBuilder.addStatement("return domainService.process(inputDomain).map(output -> $L.toDto(output))",
                outboundMapperFieldName != null ? outboundMapperFieldName : "/* mapper missing */");

        // Add @RunOnVirtualThread annotation if the property is enabled
        if (runOnVirtualThreads) {
            methodBuilder.addAnnotation(ClassName.get("io.smallrye.common.annotation", "RunOnVirtualThread"));
        }

        return methodBuilder.build();
    }

    /**
         * Build the REST resource "process" method for a ReactiveStreamingClientService which accepts a stream
         * of input DTOs and produces a single output DTO.
         *
         * <p>The generated method is public, annotated with {@code @POST} and {@code @Path("/process")}, takes a
         * {@code Multi<inputDto>} parameter named {@code inputDtos}, and returns a {@code Uni<outputDto>}.
         *
         * @param inputDtoClassName the TypeName of the input DTO type
         * @param outputDtoClassName the TypeName of the output DTO type
         * @param inboundMapperFieldName field name of the inbound mapper used to convert DTOs to domain objects; may be null
         * @param outboundMapperFieldName field name of the outbound mapper used to convert domain objects to DTOs; may be null
         * @param inputType the domain input TypeMirror, used for the generated method's domain type references; may be null
         * @param outputType the domain output TypeMirror, used for the generated method's domain type references; may be null
         * @param runOnVirtualThreads if true, annotate the generated method with {@code @RunOnVirtualThread}
         * @return a MethodSpec representing the REST {@code process} method that maps streaming inputs to a single output
         */
    private MethodSpec createReactiveStreamingClientServiceProcessMethod(
            TypeName inputDtoClassName, TypeName outputDtoClassName,
            String inboundMapperFieldName, String outboundMapperFieldName,
            TypeMirror inputType, TypeMirror outputType, boolean runOnVirtualThreads) {

        TypeName multiInputDto = ParameterizedTypeName.get(ClassName.get(Multi.class), inputDtoClassName);
        TypeName uniOutputDto = ParameterizedTypeName.get(ClassName.get(Uni.class), outputDtoClassName);

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("process")
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "POST"))
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Path"))
                .addMember("value", "$S", "/process")
                .build())
            .addModifiers(Modifier.PUBLIC)
            .returns(uniOutputDto)
            .addParameter(ParameterSpec.builder(multiInputDto, "inputDtos")
                .build());

        // Add the implementation code
        methodBuilder.addStatement("$T<$T> domainInputs = inputDtos.map(input -> $L.fromDto(input))",
                ClassName.get(Multi.class),
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                inboundMapperFieldName != null ? inboundMapperFieldName : "/* mapper missing */");

        methodBuilder.addStatement("return domainService.process(domainInputs).map(output -> $L.toDto(output))",
                outboundMapperFieldName != null ? outboundMapperFieldName : "/* mapper missing */");

        // Add @RunOnVirtualThread annotation if the property is enabled
        if (runOnVirtualThreads) {
            methodBuilder.addAnnotation(ClassName.get("io.smallrye.common.annotation", "RunOnVirtualThread"));
        }

        return methodBuilder.build();
    }

    /**
         * Builds the REST resource "process" method for a unary reactive service endpoint.
         * <p>
         * The generated method is a public POST mapped to "/process" that:
         * - accepts an input DTO,
         * - converts it to the domain input using the provided inbound mapper,
         * - delegates to domainService.process(...),
         * - maps the resulting domain output to an output DTO using the outbound mapper,
         * - and returns a `Uni<OutputDto>` containing the mapped result.
         *
         * @param inputDtoClassName the DTO type used as the method parameter
         * @param outputDtoClassName the DTO type returned inside the `Uni`
         * @param inboundMapperFieldName the injected field name of the inbound mapper used to convert DTO -> domain
         * @param outboundMapperFieldName the injected field name of the outbound mapper used to convert domain -> DTO
         * @param inputType the domain input type (used to reference the converted domain parameter)
         * @param outputType the domain output type (used for type references when mapping the result)
         * @return a MethodSpec representing the generated `process` method that returns `Uni<OutputDto>`
         */
    private MethodSpec createReactiveServiceProcessMethod(
            TypeName inputDtoClassName, TypeName outputDtoClassName,
            String inboundMapperFieldName, String outboundMapperFieldName,
            TypeMirror inputType, TypeMirror outputType, boolean runOnVirtualThreads) {

        TypeName uniOutputDto = ParameterizedTypeName.get(ClassName.get(Uni.class), outputDtoClassName);

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("process")
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "POST"))
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Path"))
                .addMember("value", "$S", "/process")
                .build())
            .addModifiers(Modifier.PUBLIC)
            .returns(uniOutputDto)
            .addParameter(inputDtoClassName, "inputDto");

        // Add the implementation code
        methodBuilder.addStatement("$T inputDomain = $L.fromDto(inputDto)",
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                inboundMapperFieldName != null ? inboundMapperFieldName : "/* mapper missing */");

        methodBuilder.addStatement("return domainService.process(inputDomain).map(output -> $L.toDto(output))",
                outboundMapperFieldName != null ? outboundMapperFieldName : "/* mapper missing */");

        // Add @RunOnVirtualThread annotation if the property is enabled
        if (runOnVirtualThreads) {
            methodBuilder.addAnnotation(ClassName.get("io.smallrye.common.annotation", "RunOnVirtualThread"));
        }

        return methodBuilder.build();
    }
    
    /**
     * Create a JAX-RS `process` method for a bidirectional reactive streaming service.
     *
     * <p>The generated method is a public POST mapped to "/process" that consumes and produces
     * "application/x-ndjson", accepts a stream (`Multi`) of input DTOs, converts each DTO to the
     * domain input using the inbound mapper, delegates to `domainService.process(...)`, and maps
     * domain outputs to output DTOs using the outbound mapper, returning a `Multi` of output DTOs.
     *
     * @param inputDtoClassName the DTO type used as the input element of the incoming `Multi`
     * @param outputDtoClassName the DTO type used as the output element of the returned `Multi`
     * @param inboundMapperFieldName name of the injected inbound mapper field used to convert DTO → domain
     * @param outboundMapperFieldName name of the injected outbound mapper field used to convert domain → DTO
     * @param inputType the domain input type (used for the generated method's internal type references)
     * @param outputType the domain output type (used for the generated method's internal type references)
     * @param runOnVirtualThreads if true, the generated method will be annotated with `@RunOnVirtualThread`
     * @return a MethodSpec describing the generated `process` endpoint method that returns `Multi<OutputDto>`
     */
    private MethodSpec createReactiveBidirectionalStreamingServiceProcessMethod(
            TypeName inputDtoClassName, TypeName outputDtoClassName,
            String inboundMapperFieldName, String outboundMapperFieldName,
            TypeMirror inputType, TypeMirror outputType, boolean runOnVirtualThreads) {

        TypeName multiInputDto = ParameterizedTypeName.get(ClassName.get(Multi.class), inputDtoClassName);
        TypeName multiOutputDto = ParameterizedTypeName.get(ClassName.get(Multi.class), outputDtoClassName);

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("process")
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "POST"))
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Path"))
                .addMember("value", "$S", "/process")
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Consumes"))
                .addMember("value", "$S",  "application/x-ndjson")
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.ws.rs", "Produces"))
                .addMember("value", "$S",  "application/x-ndjson")
                .build())
            .addAnnotation(AnnotationSpec.builder(ClassName.get("org.jboss.resteasy.reactive", "RestStreamElementType"))
                .addMember("value", "$S", "application/json")
                .build())
            .addModifiers(Modifier.PUBLIC)
            .returns(multiOutputDto)
            .addParameter(multiInputDto, "inputDtos");

        // Add the implementation code
        methodBuilder.addStatement("$T<$T> domainInputs = inputDtos.map(input -> $L.fromDto(input))",
                ClassName.get(Multi.class),
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                inboundMapperFieldName != null ? inboundMapperFieldName : "/* mapper missing */");

        methodBuilder.addStatement("return domainService.process(domainInputs).map(output -> $L.toDto(output))",
                outboundMapperFieldName != null ? outboundMapperFieldName : "/* mapper missing */");

        // Add @RunOnVirtualThread annotation if the property is enabled
        if (runOnVirtualThreads) {
            methodBuilder.addAnnotation(ClassName.get("io.smallrye.common.annotation", "RunOnVirtualThread"));
        }

        return methodBuilder.build();
    }
    
    /**
     * Checks if a service class implements the ReactiveService interface.
     * 
     * @param serviceClass the TypeElement representing the service class
     * @return true if the service implements ReactiveService, false otherwise
     */
    boolean implementsReactiveService(TypeElement serviceClass) {
        // Check if the service class implements ReactiveService
        // We need to check all interfaces implemented by the service class
        return implementsInterface(serviceClass, "org.pipelineframework.service.ReactiveService");
    }

    /**
     * Adds a generated `remoteProcess` method to the provided gRPC service builder that instantiates
     * an inline adapter configured for the step and delegates incoming requests to that adapter.
     *
     * @param grpcServiceBuilder the TypeSpec.Builder for the gRPC service class to which the method will be added
     * @param grpcAdapterClassName the base gRPC adapter class to instantiate (with appropriate type parameters)
     * @param inputGrpcType the gRPC input message type (may be null)
     * @param outputGrpcType the gRPC output message type (may be null)
     * @param inputType the domain input type (may be null)
     * @param outputType the domain output type (may be null)
     * @param serviceClass the domain service class returned by the adapter's `getService` implementation
     * @param inboundMapperType the mapper type used to convert from gRPC/DTO input to domain input (may be null)
     * @param outboundMapperType the mapper type used to convert from domain output to gRPC/DTO output (may be null)
     * @param runOnVirtualThreads if true, the generated `remoteProcess` method is annotated to run on virtual threads
     * @param returnType the reactive return wrapper type for the generated method (e.g. `Uni` or `Multi`)
     * @param parameterType the type of the single parameter accepted by the generated `remoteProcess` method
     * @param parameterName the name of the method parameter
     */
    private void createAndAddRemoteProcessMethod(TypeSpec.Builder grpcServiceBuilder,
            ClassName grpcAdapterClassName,
            TypeMirror inputGrpcType, TypeMirror outputGrpcType, TypeMirror inputType, TypeMirror outputType,
            TypeElement serviceClass,
            TypeMirror inboundMapperType, TypeMirror outboundMapperType,
            boolean runOnVirtualThreads,
            ClassName returnType,
            TypeName parameterType, String parameterName) {

        // Check that mappers exist when they will be referenced by fromGrpc/toGrpc methods
        if (inputType != null && inputGrpcType != null && inboundMapperType == null) {
            throw new IllegalStateException("inboundMapper is required when both inputType and inputGrpcType are specified");
        }
        if (outputType != null && outputGrpcType != null && outboundMapperType == null) {
            throw new IllegalStateException("outboundMapper is required when both outputType and outputGrpcType are specified");
        }

        // Create the inline adapter
        TypeSpec inlineAdapter = TypeSpec.anonymousClassBuilder("")
            .superclass(ParameterizedTypeName.get(grpcAdapterClassName,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT,
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT,
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                outputType != null ? ClassName.get(outputType) : ClassName.OBJECT))
            .addMethod(MethodSpec.methodBuilder("getService")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ClassName.get(serviceClass))
                .addStatement("return $N", "service")
                .build())
            .addMethod(MethodSpec.methodBuilder("fromGrpc")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(inputType != null ? ClassName.get(inputType) : ClassName.OBJECT)
                .addParameter(inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, "grpcIn")
                .addStatement("return $N.fromGrpcFromDto(grpcIn)", "inboundMapper")
                .build())
            .addMethod(MethodSpec.methodBuilder("toGrpc")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT)
                .addParameter(outputType != null ? ClassName.get(outputType) : ClassName.OBJECT, "output")
                .addStatement("return $N.toDtoToGrpc(output)", "outboundMapper")
                .build())
            .build();

        MethodSpec.Builder remoteProcessMethodBuilder = MethodSpec.methodBuilder("remoteProcess")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(returnType,
                outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT))
            .addParameter(parameterType, parameterName)
            .addStatement("$T adapter = $L",
                ParameterizedTypeName.get(grpcAdapterClassName,
                    inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT,
                    outputGrpcType != null ? ClassName.get(outputGrpcType) : ClassName.OBJECT,
                    inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                    outputType != null ? ClassName.get(outputType) : ClassName.OBJECT),
                inlineAdapter)
            .addStatement("return adapter.remoteProcess($N)", parameterName);

        // Add @RunOnVirtualThread annotation if the property is enabled
        if (runOnVirtualThreads) {
            remoteProcessMethodBuilder.addAnnotation(ClassName.get("io.smallrye.common.annotation", "RunOnVirtualThread"));
        }

        grpcServiceBuilder.addMethod(remoteProcessMethodBuilder.build());
    }

    /**
     * Recursively checks if a class implements a specific interface.
     *
     * @param classElement the class to check
     * @param interfaceClassName the fully qualified class name of the interface to look for
     * @return true if the class implements the interface, false otherwise
     */
    boolean implementsInterface(TypeElement classElement, String interfaceClassName) {
        // Get the interface element from the processing environment
        TypeElement interfaceElement = processingEnv.getElementUtils()
            .getTypeElement(interfaceClassName);
        
        if (interfaceElement == null) {
            if (processingEnv != null && processingEnv.getMessager() != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, 
                    "Could not find interface: " + interfaceClassName);
            }
            return false;
        }
        
        // Check direct interfaces
        for (TypeMirror interfaceType : classElement.getInterfaces()) {
            if (processingEnv.getTypeUtils().isSameType(
                    processingEnv.getTypeUtils().erasure(interfaceType),
                    processingEnv.getTypeUtils().erasure(interfaceElement.asType()))) {
                return true;
            }
        }
        
        // Check superclass interfaces recursively
        TypeMirror superClass = classElement.getSuperclass();
        if (superClass != null && superClass.getKind() != javax.lang.model.type.TypeKind.NONE) {
            TypeElement superClassElement = (TypeElement) processingEnv.getTypeUtils().asElement(superClass);
            if (superClassElement != null) {
                return implementsInterface(superClassElement, interfaceClassName);
            }
        }
        
        return false;
    }
    
    /**
     * Derives the resource path from the service class name.
     * For example, ProcessPaymentStatusService becomes /api/v1/process-payment-status
     * 
     * @param className the simple name of the service class
     * @return the derived resource path
     */
    String deriveResourcePath(String className) {
        // Remove "Service" suffix if present
        if (className.endsWith("Service")) {
            className = className.substring(0, className.length() - 7);
        }
        
        // Remove "Reactive" if present (for service names like "ProcessPaymentReactiveService")
        className = className.replace("Reactive", "");
        
        // Convert from PascalCase to kebab-case
        // Handle sequences like "ProcessPaymentStatus" -> "process-payment-status"
        String pathPart = className.replaceAll("([a-z])([A-Z])", "$1-$2")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
            .toLowerCase();
            
        return "/api/v1/" + pathPart;
    }
    
    /**
     * Derives the corresponding DTO type name for a given domain type.
     * <p>
     * The result is a fully qualified type name when a package can be produced,
     * otherwise a simple DTO type name. Common package transformations are applied:
     * ".common.domain" -> ".common.dto", ".domain" -> ".dto", and ".service" -> ".dto".
     * If the input type already ends with `Dto`, it is returned unchanged. A special
     * case maps a type whose simple name is `"domain"` to a `Dto` class in the
     * transformed package.
     *
     * @param typeMirror the domain type to map (may be a fully qualified or simple name)
     * @return the DTO type name (fully qualified when possible), or `null` if {@code typeMirror} is null
     */
    String getDtoType(TypeMirror typeMirror) {
        if (typeMirror == null || typeMirror.toString() == null) {
            return null;
        }
        
        String typeName = typeMirror.toString();
        int lastDot = typeName.lastIndexOf('.');
        String packageName = lastDot > 0 ? typeName.substring(0, lastDot) : "";
        String simpleName = lastDot > 0 ? typeName.substring(lastDot + 1) : typeName;
        
        // Check if simpleName already ends with "Dto" to avoid duplication
        if (simpleName.endsWith("Dto")) {
            // If it already ends with Dto, don't add Dto again
            return typeName;
        }
        
        // Replace domain package with dto package
        String modifiedPackageName = packageName;
        // Handle the more specific pattern first to avoid partial replacements
        if (modifiedPackageName.contains(".common.domain")) {
            modifiedPackageName = modifiedPackageName.replace(".common.domain", ".common.dto");
        }
        // Then handle general .domain
        if (modifiedPackageName.contains(".domain")) {
            modifiedPackageName = modifiedPackageName.replace(".domain", ".dto");
        } else if (modifiedPackageName.contains(".service")) {
            // For service packages, replace with .dto
            modifiedPackageName = modifiedPackageName.replace(".service", ".dto");
        }
        
        // If the simpleName is exactly "domain", change it to "Dto"
        if ("domain".equals(simpleName)) {
            // Edge case: class name is literally "domain"
            // Ensure the package has .dto in it if it doesn't already
            if (!modifiedPackageName.contains(".dto")) {
                modifiedPackageName = modifiedPackageName + ".dto";
            } else {
                return modifiedPackageName + ".Dto";
            }
        }
        
        return modifiedPackageName.isEmpty() ? simpleName + "Dto" : modifiedPackageName + "." + simpleName + "Dto";
    }
    
    /**
     * Finds the AnnotationMirror instance for a specific annotation present on an element.
     *
     * @param element the element to inspect for the annotation
     * @param annotationClass the annotation class to look for
     * @return the matching {@link AnnotationMirror} if the annotation is present on the element, or {@code null} if not found
     */
    protected AnnotationMirror getAnnotationMirror(Element element, Class<?> annotationClass) {
        String annotationClassName = annotationClass.getCanonicalName();
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
            if (annotationMirror.getAnnotationType().toString().equals(annotationClassName)) {
                return annotationMirror;
            }
        }
        return null;
    }

        /**
     * Extracts a TypeMirror value from an annotation by member name.
     *
     * @param annotation The annotation mirror to extract the value from
     * @param memberName The name of the annotation member to extract
     * @return The TypeMirror value of the annotation member, or null if not found or if it's a void type
     */
    protected TypeMirror getAnnotationValue(AnnotationMirror annotation, String memberName) {
        for (ExecutableElement executableElement : annotation.getElementValues().keySet()) {
            if (executableElement.getSimpleName().toString().equals(memberName)) {
                AnnotationValue annotationValue = annotation.getElementValues().get(executableElement);
                if (annotationValue != null) {
                    Object value = annotationValue.getValue();
                    if (value instanceof TypeMirror) {
                        return (TypeMirror) value;
                    } else if (value instanceof String className) {
                        // Handle string values that should be class names
                        if ("void".equals(className) || className.isEmpty() || "java.lang.Void".equals(className)) {
                            // Return null for void types
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

        /**
     * Extracts a String value from an annotation by member name.
     *
     * @param annotation The annotation mirror to extract the value from
     * @param memberName The name of the annotation member to extract
     * @return The String value of the annotation member, or null if not found
     */
    protected String getAnnotationValueAsString(AnnotationMirror annotation, String memberName) {
        for (ExecutableElement executableElement : annotation.getElementValues().keySet()) {
            if (executableElement.getSimpleName().toString().equals(memberName)) {
                Object value = annotation.getElementValues().get(executableElement).getValue();
                if (value instanceof String) {
                    return (String) value;
                } else if (value instanceof TypeMirror) {
                    // For class types, get the qualified name
                    return value.toString();
                }
            }
        }
        return null;
    }

        /**
     * Extracts a boolean value from an annotation by member name.
     *
     * @param annotation The annotation mirror to extract the value from
     * @param memberName The name of the annotation member to extract
     * @param defaultValue The default value to return if the annotation value is not found
     * @return The boolean value of the annotation member, or the default value if not found
     */
    protected boolean getAnnotationValueAsBoolean(AnnotationMirror annotation, String memberName, boolean defaultValue) {
        for (ExecutableElement executableElement : annotation.getElementValues().keySet()) {
            if (executableElement.getSimpleName().toString().equals(memberName)) {
                Object value = annotation.getElementValues().get(executableElement).getValue();
                if (value instanceof Boolean) {
                    return (Boolean) value;
                }
                break; // Exit after finding the element even if it's not the correct type
            }
        }
        return defaultValue;
    }

    /**
     * Generates a plugin adapter class that bridges gRPC messages to plugin implementations.
     *
     * @param serviceClass the annotated service class element
     * @param pipelineStep the PipelineStep annotation instance for the service
     * @throws IOException if writing the generated Java source file fails
     */
    protected void generatePluginAdapter(TypeElement serviceClass, PipelineStep pipelineStep) throws IOException {
        // Get the annotation mirror to extract TypeMirror values
        AnnotationMirror annotationMirror = getAnnotationMirror(serviceClass, PipelineStep.class);
        if (annotationMirror == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Could not get annotation mirror for " + serviceClass, serviceClass);
            return;
        }

        // Extract values from the annotation mirror
        TypeMirror inputType = getAnnotationValue(annotationMirror, "inputType");
        TypeMirror inboundMapperType = getAnnotationValue(annotationMirror, "inboundMapper");
        TypeMirror inputGrpcType = getAnnotationValue(annotationMirror, "inputGrpcType");

        // Use the same package as the original service but with a ".pipeline" suffix
        String fqcn = serviceClass.getQualifiedName().toString();
        String originalPackage = fqcn.substring(0, fqcn.lastIndexOf('.'));
        String pkg = String.format("%s%s", originalPackage, PIPELINE_PACKAGE_SUFFIX);
        String serviceClassName = serviceClass.getSimpleName().toString();
        String adapterClassName = serviceClassName.replace("Service", "") + "PluginAdapter";

        // Create the plugin adapter class
        TypeSpec.Builder adapterBuilder = TypeSpec.classBuilder(adapterClassName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.enterprise.context", "ApplicationScoped")).build())
            .addAnnotation(AnnotationSpec.builder(Unremovable.class).build())
            .addSuperinterface(ClassName.get(AdapterInvoker.class));

        // Add mapper field with @Inject if it exists
        String mapperFieldName = "mapper";
        if (inboundMapperType != null && !inboundMapperType.toString().equals("void")) {
            String inboundMapperPackage = inboundMapperType.toString().substring(0, inboundMapperType.toString().lastIndexOf('.'));
            String inboundMapperSimpleName = inboundMapperType.toString().substring(inboundMapperType.toString().lastIndexOf('.') + 1);
            ClassName inboundMapperClassName = ClassName.get(inboundMapperPackage, inboundMapperSimpleName);

            FieldSpec inboundMapperField = FieldSpec.builder(
                inboundMapperClassName,
                mapperFieldName)
                .addAnnotation(AnnotationSpec.builder(Inject.class).build())
                .build();
            adapterBuilder.addField(inboundMapperField);
        }

        // We no longer inject the plugin directly to avoid CDI build failures
        // Instead, we use PluginEngine for runtime resolution

        // Add plugin engine field
        FieldSpec pluginEngineField = FieldSpec.builder(
            ClassName.get(PluginEngine.class),
            "pluginEngine")
            .addAnnotation(AnnotationSpec.builder(Inject.class).build())
            .build();
        adapterBuilder.addField(pluginEngineField);


        // Add the invokeFromProto method implementation with runtime plugin resolution
        MethodSpec invokeFromProtoMethod = MethodSpec.methodBuilder("invokeFromProto")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassName.get(Uni.class), TypeName.OBJECT))
            .addParameter(ClassName.get("com.google.protobuf", "Message"), "protoMessage")
            .addStatement("$T inputDto = $N.fromGrpcFromDto(($T) protoMessage)",
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                mapperFieldName,
                inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT)
            .addStatement("$T<$T> resolvedPlugin = pluginEngine.resolveReactiveUnary($T.class)",
                ClassName.get("org.pipelineframework.plugin.api", "PluginReactiveUnary"),
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT,
                inputType != null ? ClassName.get(inputType) : ClassName.OBJECT)
            .addStatement("return resolvedPlugin.process(inputDto).replaceWith((Object) null)")
            .build();
        adapterBuilder.addMethod(invokeFromProtoMethod);

        TypeSpec adapterClass = adapterBuilder.build();

        // Write the generated class
        JavaFile javaFile = JavaFile.builder(pkg, adapterClass)
            .build();

        JavaFileObject builderFile = processingEnv.getFiler()
            .createSourceFile(pkg + "." + adapterClassName);

        try (var writer = builderFile.openWriter()) {
            javaFile.writeTo(writer);
        }
    }

    /**
     * Generates a plugin reactive service that implements ReactiveService and delegates to the plugin adapter.
     *
     * @param serviceClass the annotated service class element
     * @param pipelineStep the PipelineStep annotation instance for the service
     * @throws IOException if writing the generated Java source file fails
     */
    protected void generatePluginReactiveService(TypeElement serviceClass, PipelineStep pipelineStep) throws IOException {
        // Get the annotation mirror to extract TypeMirror values
        AnnotationMirror annotationMirror = getAnnotationMirror(serviceClass, PipelineStep.class);
        if (annotationMirror == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Could not get annotation mirror for " + serviceClass, serviceClass);
            return;
        }

        // Extract values from the annotation mirror
        TypeMirror inputGrpcType = getAnnotationValue(annotationMirror, "inputGrpcType");
        TypeMirror outputGrpcType = getAnnotationValue(annotationMirror, "outputGrpcType");
        TypeMirror stepType = getAnnotationValue(annotationMirror, "stepType");

        // Use the same package as the original service but with a ".pipeline" suffix
        String fqcn = serviceClass.getQualifiedName().toString();
        String originalPackage = fqcn.substring(0, fqcn.lastIndexOf('.'));
        String pkg = String.format("%s%s", originalPackage, PIPELINE_PACKAGE_SUFFIX);
        String serviceClassName = serviceClass.getSimpleName().toString();
        String pluginServiceClassName = serviceClassName.replace("Service", "") + "PluginReactiveService";

        // For plugin services, always use Empty as the output type since plugins perform side effects
        ClassName serviceInterface = ClassName.get("org.pipelineframework.service", "ReactiveService");
        ClassName emptyClassName = ClassName.get("com.google.protobuf", "Empty");
        TypeName serviceInterfaceWithTypes = ParameterizedTypeName.get(serviceInterface,
            inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT,
            emptyClassName);

        // Create the plugin reactive service class
        TypeSpec.Builder serviceBuilder = TypeSpec.classBuilder(pluginServiceClassName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(ClassName.get("jakarta.enterprise.context", "ApplicationScoped")).build())
            .addAnnotation(AnnotationSpec.builder(Unremovable.class).build())
            .addSuperinterface(serviceInterfaceWithTypes);

        // Add adapter field with @Inject
        String adapterClassName = serviceClassName.replace("Service", "") + "PluginAdapter";
        String adapterFieldName = "adapter";
        ClassName adapterClass = ClassName.get(pkg, adapterClassName);

        FieldSpec adapterField = FieldSpec.builder(
            adapterClass,
            adapterFieldName)
            .addAnnotation(AnnotationSpec.builder(Inject.class).build())
            .build();
        serviceBuilder.addField(adapterField);

        // Add the process method implementation
        MethodSpec.Builder processMethodBuilder = MethodSpec.methodBuilder("process")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassName.get(Uni.class), emptyClassName))
            .addParameter(inputGrpcType != null ? ClassName.get(inputGrpcType) : ClassName.OBJECT, "input");

        // For plugin services, we return an empty response (Empty) since plugins perform side effects
        processMethodBuilder
            .addStatement("return adapter.invokeFromProto(input).replaceWith($T.getDefaultInstance())", emptyClassName);

        serviceBuilder.addMethod(processMethodBuilder.build());

        TypeSpec serviceClassSpec = serviceBuilder.build();

        // Write the generated class
        JavaFile javaFile = JavaFile.builder(pkg, serviceClassSpec)
            .build();

        JavaFileObject builderFile = processingEnv.getFiler()
            .createSourceFile(pkg + "." + pluginServiceClassName);

        try (var writer = builderFile.openWriter()) {
            javaFile.writeTo(writer);
        }
    }
}