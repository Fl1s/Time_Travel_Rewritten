package com.fl1s.rewritten.timetravel.compat.voicechat;

import net.minecraft.world.phys.Vec3;

public record TimedAudioFrame(int tick, byte[] opus, Vec3 position) {}