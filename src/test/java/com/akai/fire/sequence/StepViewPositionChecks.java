package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extensions.framework.values.StepViewPosition;
import java.lang.reflect.Proxy;

public final class StepViewPositionChecks {
    public static void main(String[] args) {
        DoubleValueChangedCallback[] observers = new DoubleValueChangedCallback[2];
        int[] scrolled = {-1};
        Clip clip = (Clip) Proxy.newProxyInstance(Clip.class.getClassLoader(), new Class<?>[]{Clip.class},
                (p, method, parameters) -> {
                    if (method.getName().equals("scrollToStep")) { scrolled[0] = (int) parameters[0]; return null; }
                    if (method.getName().equals("setStepSize")) return null;
                    int field = method.getName().equals("getLoopLength") ? 0 : 1;
                    return Proxy.newProxyInstance(Clip.class.getClassLoader(),
                            new Class<?>[]{SettableBeatTimeValue.class}, (v, operation, values) -> {
                                if (operation.getName().equals("addValueObserver"))
                                    observers[field] = (DoubleValueChangedCallback) values[0];
                                return null;
                            });
                });
        StepViewPosition view = new StepViewPosition(clip, 32, "test");
        observers[0].valueChanged(16);
        observers[1].valueChanged(4);
        check(view.getStepOffset() == 16 && scrolled[0] == 16, "first page starts at loop start");
        view.scrollRight();
        check(view.getStepOffset() == 48 && view.getAvailableSteps() == 32, "second page");
        view.scrollRight();
        check(view.getStepOffset() == 48, "cannot scroll past last page");
        observers[0].valueChanged(4);
        check(view.getCurrentPage() == 0 && view.getStepOffset() == 16, "shorter child resets page");
        check(view.getAvailableSteps() == 16, "child loop length");
        view.setGridResolution(0.125);
        check(view.getStepOffset() == 32 && scrolled[0] == 32, "resolution respects loop origin");
        System.out.println("Step view checks passed: nonzero loop start, paging, shorter child clips and grid changes.");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
