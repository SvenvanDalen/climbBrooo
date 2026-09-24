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

    // Decides which view the widget should open into (issue #88: a quick glance at the
    // last-synced climbs without starting an activity). When the watch already has saved
    // routes and/or standalone saved climbs from a previous sync, there is no reason to make
    // the user sit through the phone-connect wait/retry/giveUp sequence above just to see data
    // it already has locally — jump straight to RouteListView, which lists that saved data
    // immediately and still picks up a live phone route list in the background if/when it
    // arrives. Only fall back to the SyncView wait when there is truly nothing saved yet.
    static function initialViewForSavedData(hasSavedRoutes, hasSavedClimbs) {
        return (hasSavedRoutes || hasSavedClimbs) ? :routeList : :sync;
    }

    // Fast-path variant of actionForTick (issue #131 review): getInitialView()'s
    // background LIST_ROUTES refresh, fired when the widget jumps straight to the
    // cached RouteListView, previously had no retry at all — unlike SyncView's wait
    // loop it partially replaces. This reuses the exact same tick 3/6/10 schedule so a
    // transient BT hiccup doesn't strand the user on stale data. The only difference
    // from actionForTick is vocabulary: the fast path never switches views (it's
    // already showing RouteListView), so :giveUp is renamed :stop to mean "stop
    // polling", not "fall back to another view".
    static function fastPathActionForTick(tick, received) {
        var action = actionForTick(tick, received);
        return (action == :giveUp) ? :stop : action;
    }
}
