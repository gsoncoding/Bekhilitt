package dev.bekhilitt.presentation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialogueLayoutTest {
    @Test void supportedWindowAndGuiScalesKeepWorldAndAllControlsVisible() {
        int[][] windows={{640,480},{854,480},{1280,720},{1440,900},{1920,1080},{2560,1440},{3440,1440}};
        for(var w:windows) for(int scale=1;scale<=8;scale++) {
            int width=w[0]/scale,height=w[1]/scale;
            if(width<320||height<240) continue;
            for(int lines:new int[]{0,1,4,20,300}) {
                var l=DialogueLayout.fit(width,height,lines);
                assertTrue(l.x()>=0 && l.x()+l.width()<=width);
                assertTrue(l.y()>=height/2,"At least half of the world must remain visible");
                assertTrue(l.y()+l.height()<height);
                assertTrue(l.bodyY()+l.linesPerPage()*DialogueLayout.LINE_HEIGHT<=l.buttonsY()-4);
                assertTrue(l.buttonsY()+20<=l.y()+l.height());
            }
        }
    }
    @Test void longDialogueCanBeReadForwardAndBackwardWithoutDroppingLines() {
        for(int capacity:new int[]{1,3,4,7}) {
            var p=new DialoguePager();p.layout(103,capacity);
            int seen=0;
            do { assertEquals(seen,p.from()); seen=p.to(); if(!p.more()) break;p.next(); }while(true);
            assertEquals(103,seen);
            while(p.page()>0)p.previous();
            assertEquals(0,p.from());
        }
    }
    @Test void heldMouseEnterSpaceOrArrowOnlyConsumesOneAction() {
        var p=new DialoguePager();p.layout(20,3);
        assertFalse(p.take(false));p.release();assertTrue(p.take(false));p.next();
        for(int repeat=0;repeat<100;repeat++)assertFalse(p.take(false));
        p.release();assertTrue(p.take(false));
    }
    @Test void serverPendingBlocksClicksAndResetRequiresPhysicalRelease() {
        var p=new DialoguePager();p.release();assertFalse(p.take(true));
        p.reset();assertFalse(p.take(false));p.release();assertTrue(p.take(false));
    }
    @Test void resizingNeverProducesAnEmptyOutOfRangePage() {
        var p=new DialoguePager();p.layout(80,3);while(p.more())p.next();
        p.layout(30,7);assertTrue(p.from()<p.to());assertEquals(30,p.to());
        p.layout(180,2);while(p.page()>0)p.previous();assertEquals(0,p.from());
    }
    @Test void emptyTextStillHasAnActionPage() {
        var p=new DialoguePager();p.layout(0,3);assertEquals(1,p.pages());assertFalse(p.more());
    }
}
