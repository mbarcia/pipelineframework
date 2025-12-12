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

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.junit.jupiter.api.Assertions.*;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class PluginGenerationTest {
    
    @Test
    void testPluginAdapterAndServiceGeneration() {
        // Define a simple pipeline service that would have plugin adapters generated
        JavaFileObject serviceSource = JavaFileObjects.forSourceString("test.TestService",
            "package test;\n" +
            "import org.pipelineframework.annotation.PipelineStep;\n" +
            "import org.pipelineframework.service.ReactiveService;\n" +
            "import io.smallrye.mutiny.Uni;\n" +
            "import io.quarkus.grpc.GrpcClient;\n" +
            "\n" +
            "@PipelineStep(\n" +
            "    inputType = java.lang.String.class,\n" +
            "    outputType = java.lang.Void.class,\n" +
            "    inputGrpcType = com.google.protobuf.Message.class,\n" +
            "    outputGrpcType = com.google.protobuf.Empty.class,\n" +
            "    stepType = org.pipelineframework.step.StepOneToOne.class,\n" +
            "    inboundMapper = test.TestMapper.class,\n" +
            "    outboundMapper = test.TestMapper.class,\n" +
            "    local = false,  // Make sure plugin adapters are generated\n" +
            "    grpcEnabled = false  // Disable gRPC functionality to prevent client/service generation issues\n" +
            ")\n" +
            "public class TestService implements ReactiveService<String, Void> {\n" +
            "    public Uni<Void> process(String input) {\n" +
            "        return Uni.createFrom().voidItem();\n" +
            "    }\n" +
            "}"
        );

        JavaFileObject mapperSource = JavaFileObjects.forSourceString("test.TestMapper",
            "package test;\n" +
            "import org.mapstruct.Mapper;\n" +
            "\n" +
            "@Mapper\n" +
            "public interface TestMapper {\n" +
            "    String fromGrpcFromDto(com.google.protobuf.Message message);\n" +
            "    com.google.protobuf.Message toDtoToGrpc(String domain);\n" +
            "}"
        );

        // Create the compiler with our annotation processor
        Compiler compiler = javac().withProcessors(new PipelineStepProcessor());
        
        // Compile the source
        Compilation compilation = compiler.compile(serviceSource, mapperSource);
        
        // Assertions on the compilation
        assertThat(compilation).succeeded();
        
        // Check if the plugin adapter was generated
        // This will check if the expected generated adapter file is created
        assertThat(compilation).generatedSourceFile("test.pipeline.TestPluginAdapter");

        // Check if the plugin reactive service was generated
        // This will check if the expected generated service file is created
        assertThat(compilation).generatedSourceFile("test.pipeline.TestPluginReactiveService");
    }
}