// Pure decision table for SyncView's 1 Hz wait loop. The first LIST_ROUTES can
// wake a phone whose ClimbPro process was dead; that wake takes a few seconds
// during which the request itself is dropped inside the phone SDK. A retransmit
// at 3 s and 6 s lands after the phone finished (re)connecting; from 10 s the
// widget gives up and falls back to the cached route list (same timeout as the
// old single-shot timer).
class SyncRetryPolicy {

    // Returns :wait, :retransmit or :giveUp for a 1-based tick (seconds shown).
    static function actionForTick(tick, received) {
        if (received) { return :wait; }  // onUpdate handles the view switch
        if (tick >= 10) { return :giveUp; }
        if (tick == 3 || tick == 6) { return :retransmit; }
        return :wait;
    }
}
