package acup.gunfeel;

import java.util.function.BooleanSupplier;

/** The final scheme's switch, read only when the gas asks it. */
final class BalanceFinalOn {
    private BalanceFinalOn() {
    }

    static final BooleanSupplier ON = () -> BalanceFinal.on();
}
