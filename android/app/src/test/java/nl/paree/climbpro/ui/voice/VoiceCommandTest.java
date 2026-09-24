package nl.paree.climbpro.ui.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class VoiceCommandTest {

    @Test
    public void parse_shortcutActionsWin() {
        assertEquals(VoiceCommand.START_RIDE, VoiceCommand.parse(VoiceCommand.ACTION_START_RIDE, "klim"));
        assertEquals(VoiceCommand.NEXT_CLIMB, VoiceCommand.parse(VoiceCommand.ACTION_NEXT_CLIMB, null));
    }

    @Test
    public void parse_assistantFeatureText() {
        assertEquals(VoiceCommand.NEXT_CLIMB, VoiceCommand.parse("android.intent.action.VIEW",
                "Hoe ver is de volgende KLIM"));
        assertEquals(VoiceCommand.START_RIDE, VoiceCommand.parse(null, "start mijn rit"));
        assertEquals(VoiceCommand.START_RIDE, VoiceCommand.parse(null, "ride"));
    }

    @Test
    public void parse_unknownIsNull() {
        assertNull(VoiceCommand.parse(null, null));
        assertNull(VoiceCommand.parse("android.intent.action.VIEW", "instellingen"));
    }
}
