package com.fl1s.rewritten.timetravel.util;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.TimeTravelConfig;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Consumer;

public class LoopSceneManager {
    private final TimeTravelConfig config;
    private final String scenePrefix;
    private Map<String, PlayerData> recordingPlayers;

    // Constructor to initialize recordingPlayers map
    public LoopSceneManager(TimeTravelConfig config) {
        this.config = config;
        this.scenePrefix = config.scenePrefix;
        this.recordingPlayers = new HashMap<>();
    }

    // Method to add a player to the recordingPlayers map
    public void addPlayer(String playerName, Vec3 joinPosition) {
        if (playerName == null || playerName.isEmpty()) {
            TimeTravel.LOOP_LOGGER.error("Player name is null or empty. Skipping player addition.");
            return;
        }

        CompoundTag inventoryTag = new CompoundTag();

        PlayerData playerData = new PlayerData(playerName, playerName, playerName, joinPosition, inventoryTag);

        recordingPlayers.put(playerName, playerData);
    }

    // Method to generate playerSceneName for a given player
    public String getPlayerSceneName(String playerName) {
        return (playerName.startsWith(scenePrefix)) ? playerName : (scenePrefix + "_" + playerName).toLowerCase();
    }

    // Method to get all playerSceneNames for recordingPlayers
    public List<String> getAllPlayerSceneNames() {
        List<String> playerSceneNames = new ArrayList<>();
        for (PlayerData player : recordingPlayers.values()) {
            playerSceneNames.add(getPlayerSceneName(player.getName()));
        }
        return playerSceneNames;
    }

    public void forEachPlayerSceneName(Consumer<String> action) {
        getAllPlayerSceneNames().forEach(action);
    }

    public Collection<PlayerData> getRecordingPlayers() {
        return recordingPlayers.values();
    }

    public PlayerData getRecordingPlayer(String playerName) {
        PlayerData direct = playerName == null ? null : recordingPlayers.get(playerName);
        if (direct != null) return direct;
        return getRecordingPlayers().stream().filter(playerData -> playerData.getName().equalsIgnoreCase(playerName)).findFirst().orElse(null);
    }

    // Method to perform an action for each recording player
    public void forEachRecordingPlayer(Consumer<PlayerData> action) {
        recordingPlayers.values().forEach(action);
    }

    // Method to set recording players with a new map
    public void setRecordingPlayers(Map<String, PlayerData> recordingPlayers) {
        this.recordingPlayers = recordingPlayers;
    }

    public void saveRecordingPlayers() {
        config.recordingPlayers = new HashMap<>(recordingPlayers);
    }

}
