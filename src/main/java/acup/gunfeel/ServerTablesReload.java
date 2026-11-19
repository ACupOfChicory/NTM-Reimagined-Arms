package acup.gunfeel;

/** ntm-gunfeel-server.properties read again now (ReaimTimes.reloadNow, core): the gun table and the bullet rules too. */
public final class ServerTablesReload implements ReaimTimes.Reload {
    @Override
    public void reloadNow() {
        BalanceGuns.reloadNow();
        BulletRules.reloadNow();
    }
}
