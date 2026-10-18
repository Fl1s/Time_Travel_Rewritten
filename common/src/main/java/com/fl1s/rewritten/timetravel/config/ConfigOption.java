package com.fl1s.rewritten.timetravel.config;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

public sealed interface ConfigOption {
    String category();

    String key();

    String comment();

    Object defaultValue();

    void accept(Object value);

    record Bool(String category, String key, String comment, boolean def,
                Consumer<Boolean> apply) implements ConfigOption {
        @Override
        public Object defaultValue() {
            return def;
        }

        @Override
        public void accept(Object value) {
            apply.accept((Boolean) value);
        }
    }

    record Int(String category, String key, String comment, int def, int min, int max,
               IntConsumer apply) implements ConfigOption {
        @Override
        public Object defaultValue() {
            return def;
        }

        @Override
        public void accept(Object value) {
            apply.accept(((Number) value).intValue());
        }
    }

    record Dec(String category, String key, String comment, double def, double min, double max,
               DoubleConsumer apply) implements ConfigOption {
        @Override
        public Object defaultValue() {
            return def;
        }

        @Override
        public void accept(Object value) {
            apply.accept(((Number) value).doubleValue());
        }
    }
}
