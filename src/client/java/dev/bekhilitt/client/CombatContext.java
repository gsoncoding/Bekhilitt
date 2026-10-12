package dev.bekhilitt.client;

import dev.bekhilitt.input.CombatInput;

/** Client presentation gate shared with the narrow vanilla input mixin. */
public final class CombatContext {
    public static final CombatInput INPUT = new CombatInput();
    private CombatContext() { }
}
