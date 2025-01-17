/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.typescript.codegen;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.SmithyUnstableApi;

@SmithyUnstableApi
final class TypeScriptDelegator {

    private final TypeScriptSettings settings;
    private final Model model;
    private final FileManifest fileManifest;
    private final SymbolProvider symbolProvider;
    private final List<TypeScriptIntegration> integrations;
    private final Map<String, TypeScriptWriter> writers = new HashMap<>();

    TypeScriptDelegator(
            TypeScriptSettings settings,
            Model model,
            FileManifest fileManifest,
            SymbolProvider symbolProvider,
            List<TypeScriptIntegration> integrations
    ) {
        this.settings = settings;
        this.model = model;
        this.fileManifest = fileManifest;
        this.symbolProvider = symbolProvider;
        this.integrations = integrations;
    }

    /**
     * Writes all of the pending writers and clears out the history of writers.
     */
    void flushWriters() {
        writers.forEach((filename, writer) -> fileManifest.writeFile(filename, writer.toString()));
        writers.clear();
    }

    /**
     * Gets all of the dependencies that have been registered in writers owned by the
     * delegator.
     *
     * @return Returns all the dependencies.
     */
    List<SymbolDependency> getDependencies() {
        // Always add unconditional dependencies.
        List<SymbolDependency> resolved = new ArrayList<>(TypeScriptDependency.getUnconditionalDependencies());
        writers.values().forEach(s -> resolved.addAll(s.getDependencies()));
        return resolved;
    }

    /**
     * Gets a previously created writer or creates a new one if needed.
     *
     * <p>Any imports required by the given symbol are automatically registered
     * with the writer.
     *
     * @param shape Shape to create the writer for.
     * @param provider The symbol provider to use (instead of the default one).
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    void useShapeWriter(Shape shape, SymbolProvider provider, Consumer<TypeScriptWriter> writerConsumer) {
        // Checkout/create the appropriate writer for the shape.
        Symbol symbol = provider.toSymbol(shape);
        String fileName = symbol.getDefinitionFile();
        if (!fileName.startsWith(Paths.get(".", CodegenUtils.SOURCE_FOLDER).toString())) {
            fileName = Paths.get(".", CodegenUtils.SOURCE_FOLDER, fileName).toString();
        }
        TypeScriptWriter writer = checkoutWriter(fileName);

        // Add any needed DECLARE symbols.
        writer.addImportReferences(symbol, SymbolReference.ContextOption.DECLARE);
        symbol.getDependencies().forEach(writer::addDependency);

        writer.pushState();

        // Allow integrations to do things like add onSection callbacks.
        // These onSection callbacks are removed when popState is called.
        for (TypeScriptIntegration integration : integrations) {
            integration.onShapeWriterUse(settings, model, provider, writer, shape);
        }

        writerConsumer.accept(writer);
        writer.popState();
    }

    /**
     * Gets a previously created writer or creates a new one if needed.
     *
     * <p>Any imports required by the given symbol are automatically registered
     * with the writer.
     *
     * @param shape Shape to create the writer for.
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    void useShapeWriter(Shape shape, Consumer<TypeScriptWriter> writerConsumer) {
        useShapeWriter(shape, symbolProvider, writerConsumer);
    }

    /**
     * Gets a previously created writer or creates a new one if needed
     * and adds a new line if the writer already exists.
     *
     * @param filename Name of the file to create.
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    void useFileWriter(String filename, Consumer<TypeScriptWriter> writerConsumer) {
        writerConsumer.accept(checkoutWriter(filename));
    }

    /**
     * Gets a previously created writer or creates a new one if needed
     * and adds a new line if the writer already exists.
     *
     * @param filename Name of the file to create.
     */
    TypeScriptWriter checkoutFileWriter(String filename) {
        return checkoutWriter(filename);
    }

    private TypeScriptWriter checkoutWriter(String filename) {
        String formattedFilename = Paths.get(filename).normalize().toString();
        boolean needsNewline = writers.containsKey(formattedFilename);

        TypeScriptWriter writer = writers.computeIfAbsent(formattedFilename, f -> {
            String moduleName = filename.endsWith(".ts") ? filename.substring(0, filename.length() - 3) : filename;
            return new TypeScriptWriter(moduleName);
        });

        // Add newlines/separators between types in the same file.
        if (needsNewline) {
            writer.write("\n");
        }

        return writer;
    }
}
