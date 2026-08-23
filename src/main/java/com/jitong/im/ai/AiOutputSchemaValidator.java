package com.jitong.im.ai;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;

final class AiOutputSchemaValidator {

    private static final String SCHEMA_RESOURCE = "/contracts/schemas/ai-output-v1.schema.json";

    private final Schema schema;

    AiOutputSchemaValidator() {
        try (InputStream input = AiOutputSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("AI output JSON Schema is missing");
            }
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(input, InputFormat.JSON);
        } catch (IOException exception) {
            throw new IllegalStateException("AI output JSON Schema could not be loaded", exception);
        }
    }

    void validate(String json) {
        if (!schema.validate(json, InputFormat.JSON).isEmpty()) {
            throw new AiProviderException(
                    "AI_INVALID_RESULT",
                    "The AI provider result does not match the fixed JSON schema");
        }
    }
}
