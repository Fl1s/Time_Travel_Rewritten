package com.fl1s.rewritten.timetravel.compat.voicechat;

import com.fl1s.rewritten.timetravel.data.Visit;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.compat.VoicechatImpl;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

public class TimeTravelVoicechatPlugin {
    public static String getPluginId() {
        return TimeTravel.MOD_ID;
    }

    public static void initialize(VoicechatApi api) {
        TimeTravel.LOOP_LOGGER.info("Initialised VC");
    }

    public static void registerEvents(EventRegistration reg) {
        reg.registerEvent(MicrophonePacketEvent.class, TimeTravelVoicechatPlugin::onMic);
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> {
            VoicechatImpl.serverApi = e.getVoicechat();
            VoicechatImpl.initCategory();
        });
    }

    private static void onMic(MicrophonePacketEvent event) {
        if (!TimeTravel.isLooping) return;
        if (!TimeTravel.trackVoice) return;
        if (event.getSenderConnection() == null) return;

        byte[] opusData = event.getPacket().getOpusEncodedData();
        if (opusData.length == 0) return;

        var player = TimeTravel.server.getPlayerList()
                .getPlayer(event.getSenderConnection().getPlayer().getUuid());

        if (player == null) return;

        PlayerData data =
                TimeTravel.loopSceneManager.getRecordingPlayer(player.getName().getString());

        if (data == null || !data.getActive()) return;

        Visit visit = data.getCurrentVisit();
        if (visit == null) return;

        data.addVoiceFrame(
                visit.id,
                (int) (TimeTravel.timelineTick - visit.startTick),
                opusData,
                player.position()
        );

        // Game event emission for live players (sculk/warden) is handled
        // entirely by voicechat-interaction when it is installed. When VCI
        // is NOT installed, voice game events are disabled - no sculk interaction.
    }

}
