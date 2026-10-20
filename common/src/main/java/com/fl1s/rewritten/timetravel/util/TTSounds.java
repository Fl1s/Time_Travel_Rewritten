package com.fl1s.rewritten.timetravel.util;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public final class TTSounds {
    private TTSounds() {
    }

    public static final SoundEvent TRAVEL_FORWARD = create("travel_forward");
    public static final SoundEvent TRAVEL_BACKWARD = create("travel_backward");
    public static final SoundEvent ASPECT_TRAVEL_FORWARD = create("aspect_travel_forward");
    public static final SoundEvent ASPECT_TRAVEL_BACKWARD = create("aspect_travel_backward");

    public static SoundEvent travel(boolean forward) {
        if (TimeTravel.knightOfTimeMode) {
            return forward ? ASPECT_TRAVEL_FORWARD : ASPECT_TRAVEL_BACKWARD;
        }
        return forward ? TRAVEL_FORWARD : TRAVEL_BACKWARD;
    }

    private static SoundEvent create(String name) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, name));
    }
}