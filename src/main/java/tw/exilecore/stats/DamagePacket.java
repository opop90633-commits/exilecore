package tw.exilecore.stats;

import java.util.EnumMap;
import java.util.Map;

/** 一次命中裡各傷害類型的數值。可變，計算流程一路修改它。 */
public final class DamagePacket {

    private final EnumMap<DamageType, Double> amounts = new EnumMap<>(DamageType.class);

    public DamagePacket() {
    }

    public DamagePacket(final DamagePacket other) {
        amounts.putAll(other.amounts);
    }

    public double get(final DamageType type) {
        return amounts.getOrDefault(type, 0.0);
    }

    public DamagePacket set(final DamageType type, final double amount) {
        if (amount <= 0) {
            amounts.remove(type);
        } else {
            amounts.put(type, amount);
        }
        return this;
    }

    public DamagePacket add(final DamageType type, final double amount) {
        return set(type, get(type) + amount);
    }

    public DamagePacket scale(final double factor) {
        for (final Map.Entry<DamageType, Double> entry : amounts.entrySet()) {
            entry.setValue(entry.getValue() * factor);
        }
        return this;
    }

    public double total() {
        double sum = 0;
        for (final double value : amounts.values()) {
            sum += value;
        }
        return sum;
    }

    public boolean isEmpty() {
        return total() <= 0;
    }

    public Map<DamageType, Double> asMap() {
        return Map.copyOf(amounts);
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<DamageType, Double> entry : amounts.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append("、");
            }
            builder.append(entry.getKey().displayName()).append(' ').append(Math.round(entry.getValue()));
        }
        return builder.isEmpty() ? "0" : builder.toString();
    }
}
