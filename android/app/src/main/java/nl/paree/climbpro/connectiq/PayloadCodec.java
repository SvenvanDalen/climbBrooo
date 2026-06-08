package nl.paree.climbpro.connectiq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Decodes the JSON payload produced by {@link nl.paree.climbpro.service.ClimbPayloadBuilder}
 * into a {@code Map<String,Object>} for the Connect IQ SDK to serialize as a Dictionary.
 *
 * Jackson maps JSON integers to Integer/Long (not Double), which the watch reads as
 * Toybox.Lang.Number. The watch rejects any message that is not a Dictionary, so we
 * MUST send a Map — never the raw byte[].
 */
public final class PayloadCodec {
    private PayloadCodec() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    public static Map<String, Object> decode(byte[] json) throws IOException {
        return MAPPER.readValue(json, MAP_TYPE);
    }
}
