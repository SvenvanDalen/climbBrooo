package nl.paree.climbpro.data.region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.location.Address;
import android.location.Geocoder;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowGeocoder;

import java.io.File;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
public class RegionResolverTest {

    private Application app;
    private RegionCache cache;
    private RegionResolver resolver;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        File f = new File(app.getFilesDir(), "regions_test.json");
        f.delete();
        cache = new RegionCache(f);
        resolver = new RegionResolver(app, cache);
        ShadowGeocoder.setIsPresent(true);
    }

    @After
    public void tearDown() {
        ShadowGeocoder.reset();
    }

    private void answer(Address... addresses) throws Exception {
        java.lang.reflect.Field field = RegionResolver.class.getDeclaredField("geocoder");
        field.setAccessible(true);
        shadowOf((Geocoder) field.get(resolver)).setFromLocation(java.util.Arrays.asList(addresses));
    }

    private static Address address(String code, String country, String admin) {
        Address a = new Address(Locale.US);
        a.setCountryCode(code);
        a.setCountryName(country);
        a.setAdminArea(admin);
        return a;
    }

    @Test
    public void cachedRegion_isReturnedWithoutGeocoding() {
        RegionCache.Region r = new RegionCache.Region();
        r.countryCode = "NL";
        cache.put(52.0, 5.0, r);
        ShadowGeocoder.setIsPresent(false);
        assertSame(r, resolver.resolve(52.0, 5.0));
    }

    @Test
    public void noGeocoder_returnsNull() {
        ShadowGeocoder.setIsPresent(false);
        assertNull(resolver.resolve(52.0, 5.0));
    }

    @Test
    public void resolved_isCachedWithCountryAndProvince() throws Exception {
        answer(address("BE", "België", "Luik"));

        RegionCache.Region r = resolver.resolve(50.5, 5.8);

        assertEquals("BE", r.countryCode);
        assertEquals("België", r.country);
        assertEquals("Luik", r.province);
        assertSame(r, cache.get(50.5, 5.8));
    }

    @Test
    public void missingCountryName_fallsBackToCode() throws Exception {
        answer(address("FR", null, null));
        RegionCache.Region r = resolver.resolve(45.0, 6.0);
        assertEquals("FR", r.country);
        assertNull(r.province);
    }

    @Test
    public void blankCountryCodeOrNoResults_returnNullAndCacheNothing() throws Exception {
        answer(address("  ", "Zee", null));
        assertNull(resolver.resolve(53.5, 3.0));
        answer(address(null, null, null));
        assertNull(resolver.resolve(53.5, 3.0));
        answer();
        assertNull(resolver.resolve(53.5, 3.0));
        assertNull(cache.get(53.5, 3.0));
    }

    @Test
    public void geocoderError_returnsNull() throws Exception {
        java.lang.reflect.Field field = RegionResolver.class.getDeclaredField("geocoder");
        field.setAccessible(true);
        shadowOf((Geocoder) field.get(resolver)).setErrorMessage("offline");
        assertNull(resolver.resolve(52.0, 5.0));
        assertNull(cache.get(52.0, 5.0));
    }
}
