using Toybox.Test;

// SyncRetryPolicy: pure decision table for SyncView's 1 Hz wait loop.

(:test)
function syncRetry_retransmitsAt3And6(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(3, false), :retransmit);
    Test.assertEqual(SyncRetryPolicy.actionForTick(6, false), :retransmit);
    return true;
}

(:test)
function syncRetry_waitsOnOtherTicksBeforeTimeout(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(1, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(2, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(4, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(5, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(7, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(9, false), :wait);
    return true;
}

(:test)
function syncRetry_givesUpFromTick10(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(10, false), :giveUp);
    Test.assertEqual(SyncRetryPolicy.actionForTick(11, false), :giveUp);
    return true;
}

(:test)
function syncRetry_neverActsOnceReceived(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(3, true), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(6, true), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(10, true), :wait);
    return true;
}
