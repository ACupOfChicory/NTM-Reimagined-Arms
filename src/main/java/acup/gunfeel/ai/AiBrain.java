package acup.gunfeel.ai;

/** What an AI mob decides to do with what it knows: where to go, where to aim, when to shoot. Server thread. */
@FunctionalInterface
public interface AiBrain {
    void tick(AiBot bot, long tick);

    /** it stops thinking (no one near: AiBots.active), its body left as it would stand there; nothing by default */
    default void sleep(AiBot bot, long tick) {
    }
}
