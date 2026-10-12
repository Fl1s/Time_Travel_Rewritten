package com.fl1s.rewritten.timetravel.types;

public enum SkinTypes implements TypeInterface {
    NAME,
    MINESKIN,
    FILE;

    @Override
    public String getCommandName() {
        return "skin_from_" + name().toLowerCase();
    }
}
