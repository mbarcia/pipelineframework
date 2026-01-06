package org.pipelineframework.processor.renderer;

import java.io.IOException;
import java.util.Locale;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;

import com.google.protobuf.DescriptorProtos;
import com.squareup.javapoet.*;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.util.GrpcJavaTypeResolver;
import org.pipelineframework.processor.util.OrchestratorGrpcBindingResolver;

/**
 * Generates the orchestrator server endpoint based on pipeline configuration.
 */
public class OrchestratorServerRenderer {

    private static final String RESOURCE_CLASS = "PipelineRunResource";
    private static final String GRPC_CLASS = "OrchestratorGrpcService";
    private static final String ORCHESTRATOR_SERVICE = "OrchestratorService";
    private static final String ORCHESTRATOR_METHOD = "Run";

    public void render(
        OrchestratorServerModel model,
        DescriptorProtos.FileDescriptorSet descriptorSet,
        ProcessingEnvironment processingEnv
    ) throws IOException {
        if (model == null) {
            return;
        }
        String transport = model.transport();
        boolean restMode = transport != null && "REST".equalsIgnoreCase(transport.trim());
        if (restMode) {
            writeRestResource(model, processingEnv);
        } else {
            writeGrpcService(model, descriptorSet, processingEnv);
        }
    }

    private void writeRestResource(OrchestratorServerModel model, ProcessingEnvironment processingEnv) throws IOException {
        ClassName applicationScoped = ClassName.get("jakarta.enterprise.context", "ApplicationScoped");
        ClassName inject = ClassName.get("jakarta.inject", "Inject");
        ClassName path = ClassName.get("jakarta.ws.rs", "Path");
        ClassName post = ClassName.get("jakarta.ws.rs", "POST");
        ClassName consumes = ClassName.get("jakarta.ws.rs", "Consumes");
        ClassName produces = ClassName.get("jakarta.ws.rs", "Produces");
        ClassName restStream = ClassName.get("org.jboss.resteasy.reactive", "RestStreamElementType");
        ClassName uni = ClassName.get("io.smallrye.mutiny", "Uni");
        ClassName multi = ClassName.get("io.smallrye.mutiny", "Multi");
        ClassName executionService = ClassName.get("org.pipelineframework", "PipelineExecutionService");

        ClassName inputType = ClassName.get(model.basePackage() + ".common.dto", model.inputTypeName() + "Dto");
        ClassName outputType = ClassName.get(model.basePackage() + ".common.dto", model.outputTypeName() + "Dto");

        FieldSpec executionField = FieldSpec.builder(executionService, "pipelineExecutionService", Modifier.PRIVATE)
            .addAnnotation(inject)
            .build();

        MethodSpec.Builder runMethod = MethodSpec.methodBuilder("run")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(post)
            .addAnnotation(AnnotationSpec.builder(path).addMember("value", "$S", "/run").build());

        if (model.inputStreaming() && model.outputStreaming()) {
            runMethod.addAnnotation(AnnotationSpec.builder(consumes).addMember("value", "$S", "application/x-ndjson").build());
            runMethod.addAnnotation(AnnotationSpec.builder(produces).addMember("value", "$S", "application/x-ndjson").build());
        }
        if (model.outputStreaming()) {
            runMethod.addAnnotation(AnnotationSpec.builder(restStream).addMember("value", "$S", "application/json").build());
        }

        TypeName returnType = model.outputStreaming()
            ? ParameterizedTypeName.get(multi, outputType)
            : ParameterizedTypeName.get(uni, outputType);
        runMethod.returns(returnType);

        TypeName inputParamType = model.inputStreaming()
            ? ParameterizedTypeName.get(multi, inputType)
            : inputType;
        runMethod.addParameter(inputParamType, "input");

        String methodSuffix = model.outputStreaming() ? "Streaming" : "Unary";
        if (model.inputStreaming()) {
            runMethod.addStatement("return pipelineExecutionService.executePipeline$L(input)", methodSuffix);
        } else {
            runMethod.addStatement("return pipelineExecutionService.executePipeline$L($T.createFrom().item(input))",
                methodSuffix, uni);
        }

        TypeSpec resource = TypeSpec.classBuilder(RESOURCE_CLASS)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(applicationScoped)
            .addAnnotation(AnnotationSpec.builder(path).addMember("value", "$S", "/pipeline").build())
            .addField(executionField)
            .addMethod(runMethod.build())
            .build();

        JavaFile.builder(model.basePackage() + ".orchestrator.service", resource)
            .build()
            .writeTo(processingEnv.getFiler());
    }

    private void writeGrpcService(
        OrchestratorServerModel model,
        DescriptorProtos.FileDescriptorSet descriptorSet,
        ProcessingEnvironment processingEnv
    ) throws IOException {
        if (descriptorSet == null) {
            throw new IllegalStateException("No protobuf descriptor set available for orchestrator gRPC generation.");
        }

        ClassName grpcServiceAnnotation = ClassName.get("io.quarkus.grpc", "GrpcService");
        ClassName inject = ClassName.get("jakarta.inject", "Inject");
        ClassName uni = ClassName.get("io.smallrye.mutiny", "Uni");
        ClassName multi = ClassName.get("io.smallrye.mutiny", "Multi");
        ClassName executionService = ClassName.get("org.pipelineframework", "PipelineExecutionService");

        PipelineStepModel grpcModel = new PipelineStepModel(
            ORCHESTRATOR_SERVICE,
            ORCHESTRATOR_SERVICE,
            model.basePackage() + ".orchestrator.service",
            ClassName.get(model.basePackage() + ".orchestrator.service", ORCHESTRATOR_SERVICE),
            null,
            null,
            streamingShape(model),
            java.util.Set.of(GenerationTarget.GRPC_SERVICE),
            ExecutionMode.DEFAULT,
            DeploymentRole.ORCHESTRATOR_CLIENT,
            false,
            null
        );

        OrchestratorGrpcBindingResolver resolver = new OrchestratorGrpcBindingResolver();
        var binding = resolver.resolve(
            grpcModel,
            descriptorSet,
            ORCHESTRATOR_METHOD,
            model.inputStreaming(),
            model.outputStreaming(),
            processingEnv.getMessager());

        GrpcJavaTypeResolver typeResolver = new GrpcJavaTypeResolver();
        var grpcTypes = typeResolver.resolve(binding, processingEnv.getMessager());
        ClassName inputType = grpcTypes.grpcParameterType();
        ClassName outputType = grpcTypes.grpcReturnType();
        if (inputType == null || outputType == null) {
            throw new IllegalStateException("Failed to resolve orchestrator gRPC message types from descriptors.");
        }

        ClassName implBase = grpcTypes.implBase();
        if (implBase == null) {
            implBase = ClassName.get(
                model.basePackage() + ".grpc",
                "Mutiny" + ORCHESTRATOR_SERVICE + "Grpc",
                ORCHESTRATOR_SERVICE + "ImplBase");
        }

        FieldSpec executionField = FieldSpec.builder(executionService, "pipelineExecutionService", Modifier.PRIVATE)
            .addAnnotation(inject)
            .build();

        MethodSpec.Builder runMethod = MethodSpec.methodBuilder("run")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC);

        TypeName returnType = model.outputStreaming()
            ? ParameterizedTypeName.get(multi, outputType)
            : ParameterizedTypeName.get(uni, outputType);
        runMethod.returns(returnType);

        TypeName inputParamType = model.inputStreaming()
            ? ParameterizedTypeName.get(multi, inputType)
            : inputType;
        runMethod.addParameter(inputParamType, "input");

        String methodSuffix = model.outputStreaming() ? "Streaming" : "Unary";
        if (model.inputStreaming()) {
            runMethod.addStatement("return pipelineExecutionService.executePipeline$L(input)", methodSuffix);
        } else {
            runMethod.addStatement("return pipelineExecutionService.executePipeline$L($T.createFrom().item(input))",
                methodSuffix, uni);
        }

        TypeSpec service = TypeSpec.classBuilder(GRPC_CLASS)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(grpcServiceAnnotation)
            .superclass(implBase)
            .addField(executionField)
            .addMethod(runMethod.build())
            .build();

        JavaFile.builder(model.basePackage() + ".orchestrator.service", service)
            .build()
            .writeTo(processingEnv.getFiler());
    }

    private StreamingShape streamingShape(OrchestratorServerModel model) {
        if (model.inputStreaming() && model.outputStreaming()) {
            return StreamingShape.STREAMING_STREAMING;
        }
        if (model.inputStreaming()) {
            return StreamingShape.STREAMING_UNARY;
        }
        if (model.outputStreaming()) {
            return StreamingShape.UNARY_STREAMING;
        }
        return StreamingShape.UNARY_UNARY;
    }

    public record OrchestratorServerModel(
        String basePackage,
        String transport,
        String inputTypeName,
        String outputTypeName,
        boolean inputStreaming,
        boolean outputStreaming
    ) {
        public String normalizedTransport() {
            if (transport == null) {
                return "GRPC";
            }
            String trimmed = transport.trim();
            return trimmed.isEmpty() ? "GRPC" : trimmed.toUpperCase(Locale.ROOT);
        }
    }
}
