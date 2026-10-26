package app.pixelpantry.tv.player

import app.pixelpantry.tv.player.LoadErrorBudget.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadErrorBudgetTest {
    private val budget = LoadErrorBudget(giveUpAfterMs = 8_000, newRunGapMs = 15_000)

    @Test fun firstErrorRetriesAfterASecond() {
        assertEquals(Decision.Retry(1_000), budget.onError(bytesLoaded = 1_000, now = 100_000))
    }

    @Test fun errorsWithinTheBudgetRetryEveryTwoSeconds() {
        budget.onError(1_000, 100_000)
        assertEquals(Decision.Retry(2_000), budget.onError(1_000, 101_000))
        assertEquals(Decision.Retry(2_000), budget.onError(1_000, 103_000))
        assertEquals(Decision.Retry(2_000), budget.onError(1_000, 107_999))
    }

    @Test fun anErrorEightSecondsIntoARunWithoutProgressGivesUp() {
        budget.onError(1_000, 100_000)
        budget.onError(1_000, 104_000)
        assertEquals(Decision.GiveUp, budget.onError(1_000, 108_000))
    }

    @Test fun progressSinceTheLastErrorStartsAFreshRun() {
        budget.onError(1_000, 100_000)
        budget.onError(1_000, 106_000)
        // Data arrived, then a new failure 3 s later: not 9 s into the old run.
        assertEquals(Decision.Retry(1_000), budget.onError(5_000, 109_000))
        assertEquals(Decision.Retry(2_000), budget.onError(5_000, 116_000))
        assertEquals(Decision.GiveUp, budget.onError(5_000, 117_000))
    }

    @Test fun aQuietGapOfFifteenSecondsStartsAFreshRun() {
        budget.onError(1_000, 100_000)
        budget.onError(1_000, 107_000)
        assertEquals(Decision.Retry(1_000), budget.onError(1_000, 122_001))
    }

    @Test fun aSecondOutageMinutesLaterGetsItsOwnBudget() {
        budget.onError(1_000, 100_000)
        budget.onError(1_000, 102_000) // first blip ridden out
        assertEquals(Decision.Retry(1_000), budget.onError(90_000_000, 400_000))
        assertEquals(Decision.Retry(2_000), budget.onError(90_000_000, 403_000))
    }

    @Test fun resetForgetsTheRun() {
        budget.onError(1_000, 100_000)
        budget.onError(1_000, 107_000)
        budget.reset()
        assertEquals(Decision.Retry(1_000), budget.onError(0, 108_500))
    }
}
