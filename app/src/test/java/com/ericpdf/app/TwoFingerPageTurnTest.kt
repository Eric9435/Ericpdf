package com.ericpdf.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFingerPageTurnTest {
    @Test fun swipingLeftGoesForward() {
        assertEquals(1,TwoFingerPageTurn.direction(-160f,15f,false,1f,94f))
    }
    @Test fun swipingRightGoesBackward() {
        assertEquals(-1,TwoFingerPageTurn.direction(160f,15f,false,1f,94f))
    }
    @Test fun shortOrVerticalMovementDoesNotChangePage() {
        assertEquals(0,TwoFingerPageTurn.direction(-90f,10f,false,1f,94f))
        assertEquals(0,TwoFingerPageTurn.direction(-200f,200f,false,1f,94f))
    }
    @Test fun pinchAndZoomNeverTurnPage() {
        assertEquals(0,TwoFingerPageTurn.direction(-180f,0f,true,1f,94f))
        assertEquals(0,TwoFingerPageTurn.direction(-180f,0f,false,1.8f,94f))
    }
}
