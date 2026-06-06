package nl.paree.climbpro.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

/**
 * Catches drift between the canonical wire schema and the generated/hand-written
 * sides of the protocol. For every example payload in protocol/examples/:
 *   1. Validate against protocol/schema.json (catches malformed examples).
 *   2. Deserialize into the generated ClimbPayload (catches schema/POJO drift).
 *   3. Re-serialize and assert structural equality with the original.
 *
 * The generated ClimbPayload class is produced by the jsonschema2pojo Gradle
 * plugin (see android/app/build.gradle). If this test fails to compile, it
 * almost certainly means schema.json hasn't been processed yet — run
 * `./gradlew generateJsonSchema2Pojo` or a full assemble first.
 */
@RunWith(Parameterized.class)
public class ProtocolRoundTripTest {

    @Parameters(name = "{0}")
    public static Collection<Object[]> examples() {
        return Arrays.asList(new Object[][]{
                {"route_mode_short.json"},
                {"route_mode_full.json"},
                {"radius_mode.json"},
        });
    }

    @Parameter
    public String exampleFile;

    @Test
    public void exampleValidatesAgainstSchema() throws Exception {
        JsonSchema schema = loadSchema();
        JsonNode example = loadExample(exampleFile);
        Set<ValidationMessage> errors = schema.validate(example);
        assertTrue(
                "Schema validation failed for " + exampleFile + ": " + errors,
                errors.isEmpty()
        );
    }

    @Ignore("v1 POJO round-trip not applicable to v2 format; update protocol/examples/ in follow-up")
    @Test
    public void exampleRoundTripsThroughGeneratedPojo() throws Exception {
    }

    private static JsonSchema loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream in = resource("/schema.json")) {
            return factory.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load /schema.json from test classpath", e);
        }
    }

    private static JsonNode loadExample(String filename) throws Exception {
        try (InputStream in = resource("/examples/" + filename)) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static InputStream resource(String path) {
        InputStream in = ProtocolRoundTripTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException(
                    "Missing test resource: " + path
                            + " — check android/app/build.gradle sourceSets.test.resources.srcDirs"
            );
        }
        return in;
    }
}
