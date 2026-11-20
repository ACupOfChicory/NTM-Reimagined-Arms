package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

// an AI mob's plain state in core, off the game: a bot with no mover, gunner or the rest (none asked for here)
class AiBotBaseTest {
    private static final Body BODY = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
        throw new AssertionError("the body not asked: " + m.getName());
    });

    private static final class Bot extends AiBotBase {
        Bot() {
            this(BODY);
        }

        Bot(Body body) {
            super(body, SensesTest.P);
        }

        @Override
        public AiRole role() {
            throw new AssertionError();
        }

        @Override
        public boolean soldier() {
            throw new AssertionError();
        }

        @Override
        public boolean onTheMove() {
            throw new AssertionError();
        }

        @Override
        public boolean inCover() {
            throw new AssertionError();
        }

        @Override
        public boolean searching() {
            throw new AssertionError();
        }

        @Override
        public boolean fightsFallen(int target) {
            throw new AssertionError();
        }

        @Override
        public boolean fallingBack() {
            throw new AssertionError();
        }

        @Override
        public boolean marksman() {
            throw new AssertionError();
        }

        @Override
        public boolean perched() {
            throw new AssertionError();
        }

        @Override
        public boolean atPerch() {
            throw new AssertionError();
        }

        @Override
        public boolean grenadier() {
            throw new AssertionError();
        }

        @Override
        public Object serverLevel() {
            throw new AssertionError();
        }

        @Override
        public acup.gunfeel.host.WorldView world() {
            throw new AssertionError();
        }

        @Override
        public AiMoverBase mover() {
            throw new AssertionError();
        }

        @Override
        public AiGunnerBase gunner() {
            throw new AssertionError();
        }

        @Override
        public AiProneBase prone() {
            throw new AssertionError();
        }

        @Override
        public AiStoopBase stoop() {
            throw new AssertionError();
        }

        @Override
        public AiDashBase dash() {
            throw new AssertionError();
        }
    }

    /** a bot with no mover, gunner or the rest */
    static AiBotBase bot() {
        return new Bot();
    }

    /** a bot of this body */
    static AiBotBase bot(Body body) {
        return new Bot(body);
    }

    @AfterEach
    void skill() {
        AiSkill.override = null;
    }

    @Test
    void madeAsTheGameMadeIt() {
        Bot b = new Bot();
        assertSame(BODY, b.body);
        assertSame(SensesTest.P, b.profile);
        assertTrue(b.memory.contacts().isEmpty());
        assertTrue(b.active());
        assertEquals(Long.MIN_VALUE, b.redFrom);
        assertEquals(Long.MIN_VALUE, b.orderFailedUntil);
        assertEquals(Long.MIN_VALUE, b.supplyNext);
        assertEquals(0L, b.supplyAt);
        assertEquals(Long.MIN_VALUE / 2, b.suppliedAt);
        assertEquals(Long.MIN_VALUE / 2, b.saidAt);
        assertEquals(Long.MIN_VALUE / 2, b.gestureAt);
        assertEquals(Long.MIN_VALUE, b.stackUntil);
        assertEquals(Long.MIN_VALUE, b.holdAt);
        assertEquals(Long.MIN_VALUE, b.holdSince);
        assertSame(AiRole.RIFLE, b.role);
        assertEquals(null, b.assigned);
    }

    @Test
    void rolesByWord() {
        assertSame(AiRole.BASE, AiRole.named("Base"));
        assertSame(AiRole.MEDIC, AiRole.named("medic"));
        assertEquals(null, AiRole.named("sniper"));
        assertEquals(java.util.List.of("rifle", "base", "supplier", "marksman", "grenadier", "medic", "auto"), AiRole.words());
    }

    @Test
    void clearedOverForANewLoss() {
        Bot b = new Bot();
        TargetMemory.Contact c = new TargetMemory.Contact(7);
        c.lostTick = 100;
        LongOpenHashSet cleared = b.clearedFor(c);
        cleared.add(42);
        // the same loss: what it looked into kept
        assertTrue(b.clearedFor(c).contains(42));
        // the same one lost again: over
        c.lostTick = 200;
        assertTrue(b.clearedFor(c).isEmpty());
        b.cleared.add(43);
        // another one lost: over
        TargetMemory.Contact d = new TargetMemory.Contact(8);
        d.lostTick = 200;
        assertTrue(b.clearedFor(d).isEmpty());
        assertSame(b.cleared, b.clearedFor(d));
    }

    @Test
    void aGestureKept() {
        // no server settings (no game folder): gestures on, by default
        Bot b = new Bot();
        b.gesture((byte) 3, 25, 1000);
        assertEquals(3, b.gesture);
        assertEquals(1000, b.gestureAt);
        assertEquals(25, b.gestureTicks);
    }

    @Test
    void eyesBySkill() {
        Bot b = new Bot();
        AiSkill.override = AiSkill.REGULAR;
        assertEquals(SensesTest.P, b.eyes(), "a regular's: the profile's own");
        AiSkill.override = AiSkill.RECRUIT;
        assertEquals(SensesTest.P.reaction() + AiSkill.RECRUIT.notice() - AiSkill.REGULAR.notice(), b.eyes().reaction());
        assertSame(b.eyes(), b.eyes(), "made once for the skill");
        AiSkill.override = AiSkill.VETERAN;
        assertEquals(SensesTest.P.reaction() + AiSkill.VETERAN.notice() - AiSkill.REGULAR.notice(), b.eyes().reaction());
    }
}
