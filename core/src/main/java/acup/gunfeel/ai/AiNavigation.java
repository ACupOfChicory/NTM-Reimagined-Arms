package acup.gunfeel.ai;

/** A mob's navigation that follows the mob AI's own paths: the game does not search a path of its own for it again. */
public interface AiNavigation {
    void gunfeel$setAiOwned(boolean owned);

    boolean gunfeel$aiOwned();
}
