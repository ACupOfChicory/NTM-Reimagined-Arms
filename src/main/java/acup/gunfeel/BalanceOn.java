package acup.gunfeel;

import java.util.function.BooleanSupplier;

/**
 * The balance's feature on (Feature.BALANCE.on()) as core asks it, where the old code asked it: one supplier, a lambda
 * that catches nothing. A class of its own with nothing else static, so taking it starts no other class's clinit.
 */
final class BalanceOn {
    private BalanceOn() {
    }

    static final BooleanSupplier ON = () -> acup.gunfeel.compat.Feature.BALANCE.on();
}
