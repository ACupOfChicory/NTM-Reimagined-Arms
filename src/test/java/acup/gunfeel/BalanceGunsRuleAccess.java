package acup.gunfeel;

// The tests outside acup.gunfeel: BalanceGuns.share with the table's mults (a line of no other change)
public final class BalanceGunsRuleAccess {
    private BalanceGunsRuleAccess() {
    }

    public static float share(float fromMult, float fromTable, float asMult, float asTable) {
        return BalanceGuns.share(fromMult, new BalanceGunsRule.Shot(fromTable, 0F, 0F), asMult, new BalanceGunsRule.Shot(asTable, 0F, 0F));
    }
}
