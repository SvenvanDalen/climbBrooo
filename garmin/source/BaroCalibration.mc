// Issue #86: barometric drift correction. At each calibration point the payload carries the
// route's known elevation ("ce"); the difference to the watch's raw altitude is the sensor
// drift. Pure functions, unit-tested in garmin/test/BaroCalibrationTest.mc.

const BARO_MAX_OFFSET_M = 200;   // beyond this the sample is a bad route/sensor, not drift
const BARO_OUTLIER_M    = 40;    // a sample this far from the running offset is ignored
const BARO_MAX_SAMPLES  = 8;     // running-mean window: newer points still move the offset

// Returns [offset, samples]. offset = known - measured, averaged over the last few points.
function baroUpdate(prevOffset, prevSamples, measured, known) {
    var sample = (known - measured).toFloat();
    if (sample > BARO_MAX_OFFSET_M || sample < -BARO_MAX_OFFSET_M) {
        return [prevOffset, prevSamples];
    }
    if (prevSamples <= 0) { return [sample, 1]; }
    var d = sample - prevOffset;
    if (d > BARO_OUTLIER_M || d < -BARO_OUTLIER_M) {
        return [prevOffset, prevSamples];
    }
    var n = prevSamples < BARO_MAX_SAMPLES ? prevSamples : BARO_MAX_SAMPLES - 1;
    var offset = (prevOffset * n + sample) / (n + 1);
    return [offset, n + 1];
}

function baroCorrect(raw, offset) {
    return raw + offset;
}
