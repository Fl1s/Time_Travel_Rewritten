package com.fl1s.rewritten.fabric.compat.voicechat;

import com.fl1s.rewritten.timetravel.compat.voicechat.TimeTravelVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;

public class TimeTravelVoicechatPluginFabric implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return TimeTravelVoicechatPlugin.getPluginId();
    }

    @Override
    public void initialize(VoicechatApi api) {
        TimeTravelVoicechatPlugin.initialize(api);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        TimeTravelVoicechatPlugin.registerEvents(registration);
    }
}
