package org.pipelineframework.processor.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.StandardLocation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.pipelineframework.config.pipeline.PipelineYamlConfig;
import org.pipelineframework.config.pipeline.PipelineYamlConfigLoader;
import org.pipelineframework.config.pipeline.PipelineYamlConfigLocator;
import org.pipelineframework.config.pipeline.PipelineYamlStep;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.GenerationTarget;
import org.pipelineframework.processor.ir.PipelineStepModel;
import org.pipelineframework.processor.ir.TypeMapping;

/**
 * Writes pipeline telemetry metadata for item-boundary inference.
 */
public class PipelineTelemetryMetadataGenerator {

    private static final String RESOURCE_PATH = "META-INF/pipeline/telemetry.json";
    private static final String ITEM_TYPE_KEY = "pipeline.telemetry.item-type";

    private final ProcessingEnvironment processingEnv;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Creates a new metadata generator.
     *
     * @param processingEnv the processing environment for compiler utilities and messaging
     */
    public PipelineTelemetryMetadataGenerator(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
    }

    /**
     * Writes item-boundary metadata to META-INF/pipeline/telemetry.json.
     *
     * @param ctx the compilation context
     * @throws IOException if writing the resource fails
     */
    public void writeTelemetryMetadata(PipelineCompilationContext ctx) throws IOException {
        if (!ctx.isOrchestratorGenerated()) {
            return;
        }
        String itemType = loadItemType(ctx);
        if (itemType == null || itemType.isBlank()) {
            return;
        }
        List<PipelineStepModel> models = filterClientModels(ctx);
        if (models.isEmpty()) {
            return;
        }
        List<PipelineStepModel> ordered = orderBaseSteps(ctx, models);
        String producer = findProducerStep(ordered, itemType, ctx.isTransportModeGrpc());
        String consumer = findConsumerStep(ordered, itemType, ctx.isTransportModeGrpc());
        if (producer == null && consumer == null) {
            return;
        }

        TelemetryMetadata metadata = new TelemetryMetadata(itemType, producer, consumer);
        StringWriter writer = new StringWriter();
        writer.write(gson.toJson(metadata));

        javax.tools.FileObject resourceFile = processingEnv.getFiler()
            .createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_PATH, (javax.lang.model.element.Element[]) null);
        try (var output = resourceFile.openWriter()) {
            output.write(writer.toString());
        }
    }

    private List<PipelineStepModel> filterClientModels(PipelineCompilationContext ctx) {
        List<PipelineStepModel> models = ctx.getStepModels();
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        GenerationTarget target = ctx.isTransportModeGrpc() ? GenerationTarget.CLIENT_STEP : GenerationTarget.REST_CLIENT_STEP;
        return models.stream()
            .filter(model -> model.enabledTargets().contains(target))
            .filter(model -> !model.sideEffect())
            .toList();
    }

    private String loadItemType(PipelineCompilationContext ctx) {
        Properties properties = new Properties();
        try {
            properties = loadApplicationProperties(ctx);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.WARNING,
                "Failed to read application.properties for telemetry item type: " + e.getMessage());
        }
        String raw = properties.getProperty(ITEM_TYPE_KEY);
        return raw == null ? null : raw.trim();
    }

    private Properties loadApplicationProperties(PipelineCompilationContext ctx) throws IOException {
        Properties properties = new Properties();
        for (Path baseDir : getBaseDirectories(ctx)) {
            Path propertiesPath = baseDir.resolve("src/main/resources/application.properties");
            if (Files.exists(propertiesPath) && Files.isReadable(propertiesPath)) {
                try (InputStream input = Files.newInputStream(propertiesPath)) {
                    properties.load(input);
                    return properties;
                }
            }
        }

        try {
            var resource = processingEnv.getFiler()
                .getResource(StandardLocation.SOURCE_PATH, "", "application.properties");
            try (InputStream input = resource.openInputStream()) {
                properties.load(input);
            }
        } catch (Exception e) {
            // Ignore when the resource is not available.
        }

        return properties;
    }

    private Set<Path> getBaseDirectories(PipelineCompilationContext ctx) {
        Set<Path> baseDirs = new LinkedHashSet<>();
        if (ctx != null && ctx.getModuleDir() != null) {
            baseDirs.add(ctx.getModuleDir());
        }
        String multiModuleDir = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModuleDir != null && !multiModuleDir.isBlank()) {
            baseDirs.add(Paths.get(multiModuleDir));
        }
        baseDirs.add(Paths.get(System.getProperty("user.dir", ".")));
        return baseDirs;
    }

    private List<PipelineStepModel> orderBaseSteps(PipelineCompilationContext ctx, List<PipelineStepModel> models) {
        PipelineYamlConfig config = loadPipelineConfig(ctx);
        if (config == null || config.steps() == null || config.steps().isEmpty()) {
            return models;
        }
        List<PipelineStepModel> remaining = new ArrayList<>(models);
        List<PipelineStepModel> ordered = new ArrayList<>();
        for (PipelineYamlStep step : config.steps()) {
            if (step == null || step.name() == null) {
                continue;
            }
            String token = toClassToken(step.name());
            if (token.isBlank()) {
                continue;
            }
            PipelineStepModel match = selectBestMatch(remaining, token, ctx.isTransportModeGrpc());
            if (match != null) {
                ordered.add(match);
                remaining.remove(match);
            }
        }
        ordered.addAll(remaining);
        return ordered;
    }

    private PipelineYamlConfig loadPipelineConfig(PipelineCompilationContext ctx) {
        PipelineYamlConfigLocator locator = new PipelineYamlConfigLocator();
        Path moduleDir = ctx.getModuleDir();
        if (moduleDir == null) {
            return null;
        }
        var configPath = locator.locate(moduleDir);
        if (configPath.isEmpty()) {
            return null;
        }
        PipelineYamlConfigLoader loader = new PipelineYamlConfigLoader();
        return loader.load(configPath.get());
    }

    private PipelineStepModel selectBestMatch(List<PipelineStepModel> candidates, String token, boolean grpcTransport) {
        PipelineStepModel best = null;
        int bestLength = -1;
        for (PipelineStepModel candidate : candidates) {
            String normalized = normalizeStepToken(resolveClientStepClassName(candidate, grpcTransport));
            if (normalized.contains(token) && token.length() > bestLength) {
                best = candidate;
                bestLength = token.length();
            }
        }
        return best;
    }

    private String normalizeStepToken(String className) {
        String simple = className;
        int lastDot = simple.lastIndexOf('.');
        if (lastDot != -1) {
            simple = simple.substring(lastDot + 1);
        }
        simple = simple.replaceAll("(Service|GrpcClientStep|RestClientStep)(_Subclass)?$", "");
        return toClassToken(simple);
    }

    private String toClassToken(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^A-Za-z0-9]", "");
    }

    private String findProducerStep(List<PipelineStepModel> ordered, String itemType, boolean grpcTransport) {
        for (PipelineStepModel model : ordered) {
            if (matchesType(model.outputMapping(), itemType)) {
                return resolveClientStepClassName(model, grpcTransport);
            }
        }
        return null;
    }

    private String findConsumerStep(List<PipelineStepModel> ordered, String itemType, boolean grpcTransport) {
        for (PipelineStepModel model : ordered) {
            if (matchesType(model.inputMapping(), itemType)) {
                return resolveClientStepClassName(model, grpcTransport);
            }
        }
        return null;
    }

    private boolean matchesType(TypeMapping mapping, String itemType) {
        if (mapping == null || mapping.domainType() == null || itemType == null) {
            return false;
        }
        return itemType.equals(mapping.domainType().toString());
    }

    private String resolveClientStepClassName(PipelineStepModel model, boolean grpcTransport) {
        String suffix = grpcTransport ? "GrpcClientStep" : "RestClientStep";
        return model.servicePackage() + ".pipeline." +
            model.generatedName().replace("Service", "") + suffix;
    }

    private record TelemetryMetadata(String itemType, String producerStep, String consumerStep) {
    }
}
