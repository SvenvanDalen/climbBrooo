using Toybox.Lang;

// Issue #26: a vibration pattern per climb type for the climb-start alert, so the rider can
// tell a short steep ramp from a long drag without looking at the screen. Watch-only: the
// type is derived from the length and average gradient the payload already carries.
//
// Pure functions only (no Attention/Properties access) so they are unit-testable -- see
// garmin/test/ClimbVibeTest.mc. ClimbProView turns the pattern into Attention.VibeProfiles.

const CLIMB_TYPE_REGULAR = 0;
const CLIMB_TYPE_SHORT_STEEP = 1;   // "kort-steil"
const CLIMB_TYPE_LONG = 2;          // "lang-sleur"

const CLIMB_LONG_M = 5000;            // from this length a climb is a long drag
const CLIMB_SHORT_M = 2500;           // below this length ...
const CLIMB_STEEP_X10 = 70;           // ... and at least 7.0 % average it is short and steep

// Vibration choices ("vibeShortSteep", "vibeLong", "vibeRegular" app settings).
const VIBE_DEFAULT = 0;        // the standard climb-start alert (or the distinct tone, #83)
const VIBE_SHORT_DOUBLE = 1;   // two quick pulses
const VIBE_LONG_SINGLE = 2;    // one long pulse
const VIBE_TRIPLE = 3;         // three medium pulses

// Length wins over steepness: a 6 km climb at 9 % is announced as a long climb.
function climbTypeOf(lenM, avgGradX10) {
    if (lenM >= CLIMB_LONG_M) { return CLIMB_TYPE_LONG; }
    if (lenM < CLIMB_SHORT_M && avgGradX10 >= CLIMB_STEEP_X10) { return CLIMB_TYPE_SHORT_STEEP; }
    return CLIMB_TYPE_REGULAR;
}

// Pattern for a choice as [dutyCycle, ms, dutyCycle, ms, ...], or null for VIBE_DEFAULT and
// unknown values (the caller then plays the standard alert).
function climbVibePattern(choice) {
    if (choice == VIBE_SHORT_DOUBLE) { return [100, 150, 0, 120, 100, 150]; }
    if (choice == VIBE_LONG_SINGLE) { return [100, 1200]; }
    if (choice == VIBE_TRIPLE) { return [100, 300, 0, 150, 100, 300, 0, 150, 100, 300]; }
    return null;
}
