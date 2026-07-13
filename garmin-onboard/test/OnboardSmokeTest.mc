using Toybox.Test;
using Toybox.Graphics as Gfx;

function onbMakeDc() {
    var ref = Gfx.createBufferedBitmap({:width => 218, :height => 218});
    return ref.get().getDc();
}

(:test)
function onboard_smoke_viewRenders(logger) {
    var v = new OnboardView();
    v.onUpdate(onbMakeDc());
    return true;
}
