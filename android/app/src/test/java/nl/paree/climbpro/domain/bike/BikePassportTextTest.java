package nl.paree.climbpro.domain.bike;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.bike.BikePassport;

import org.junit.Test;

import java.util.Arrays;

public class BikePassportTextTest {

    @Test
    public void formatListsOnlyFilledFieldsAndAttachments() {
        BikePassport p = new BikePassport();
        p.name = "Racefiets";
        p.brand = "Canyon";
        p.model = "Ultimate CF SL";
        p.frameNumber = "WAC123456";
        p.purchasePrice = "€ 2.499";
        p.color = " ";
        p.photoFileNames = Arrays.asList("a.jpg", "b.jpg");
        p.receiptFileName = "r.jpg";
        assertEquals("Fietspaspoort: Racefiets\n"
                + "Merk: Canyon\n"
                + "Model: Ultimate CF SL\n"
                + "Framenummer: WAC123456\n"
                + "Aankoopprijs: € 2.499\n"
                + "\nBijlagen: 2 foto's en aankoopbewijs\n", BikePassportText.format(p));
    }

    @Test
    public void formatWithoutAttachments() {
        BikePassport p = new BikePassport();
        p.name = "MTB";
        assertEquals("Fietspaspoort: MTB\n", BikePassportText.format(p));
        p.receiptFileName = "r.jpg";
        assertEquals("Fietspaspoort: MTB\n\nBijlagen: aankoopbewijs\n",
                BikePassportText.format(p));
    }

    @Test
    public void summaryNudgesForMissingFrameNumber() {
        BikePassport p = new BikePassport();
        p.name = "X";
        assertEquals("framenummer ontbreekt", BikePassportText.summary(p));
        p.brand = "Trek";
        p.frameNumber = "F1";
        assertEquals("Trek · frame F1", BikePassportText.summary(p));
    }
}
