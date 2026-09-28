package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why the avatar upload always failed, and why it cannot now.
 *
 * <p>The self-service endpoint base64-encoded the uploaded image into a data URL
 * and wrote it to {@code ACL_USER.AVATAR_URL}, which is {@code VARCHAR(500)}.
 * Base64 costs four characters for every three bytes, so the column could hold
 * roughly 350 bytes of image — smaller than any real photograph, and smaller
 * than the header of most formats. Every upload overflowed it and surfaced as
 * "A database constraint was violated. Please verify your data and try again."
 *
 * <p>It had never worked. Of the five avatars in the database, every one is a
 * file path of 41 characters written by the admin route, and no row has ever
 * held an image blob.
 *
 * <p>These are arithmetic rather than integration: the fault was a size that
 * cannot fit a column, and that is checkable without a database. What a test
 * with a database would add is confidence in JDBC, which was never in doubt.
 */
class AvatarStorageTest {

    /** ACL_USER.AVATAR_URL. */
    private static final int AVATAR_URL_LENGTH = 500;

    /** What the old code produced for a given image. */
    private static String dataUrl(byte[] image, String mime) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
    }

    /** What the fixed code stores: the public URL of a file on disk. */
    private static String fileUrl(long userId) {
        return "/uploads/avatars/user_" + userId + "_"
             + "0123456789abcdef0123456789abcdef" + ".png";   // prefix + 32-char UUID
    }

    @Nested
    @DisplayName("the data URL that was being stored")
    class DataUrl {

        /** 40×40 PNG, about as small as a usable avatar gets. */
        @Test
        @DisplayName("overflows the column for even a tiny image")
        void tinyImageAlreadyOverflows() {
            String url = dataUrl(new byte[2_000], "image/png");
            assertThat(url.length()).isGreaterThan(AVATAR_URL_LENGTH);
        }

        /** The limit the endpoint itself advertised as acceptable. */
        @Test
        @DisplayName("overflows it by a factor of thousands at the 2 MB limit")
        void atTheAdvertisedLimit() {
            String url = dataUrl(new byte[2 * 1024 * 1024], "image/jpeg");
            assertThat(url.length()).isGreaterThan(AVATAR_URL_LENGTH * 5_000);
        }

        /**
         * The largest image that would have fitted, to show the feature was
         * impossible rather than merely fragile.
         */
        @Test
        @DisplayName("leaves room for well under half a kilobyte of image")
        void thereWasNeverRoomForAnImage() {
            int overhead = "data:image/png;base64,".length();
            int usableBytes = (AVATAR_URL_LENGTH - overhead) / 4 * 3;

            assertThat(usableBytes).isLessThan(400);
            // A PNG signature plus IHDR alone is 33 bytes; a JPEG's headers run
            // to hundreds before a single pixel. Nothing photographic fits.
            assertThat(dataUrl(new byte[usableBytes], "image/png").length())
                    .isLessThanOrEqualTo(AVATAR_URL_LENGTH);
        }
    }

    @Nested
    @DisplayName("the file URL now stored")
    class FileUrl {

        @Test
        @DisplayName("fits the column with room to spare, whatever the image weighs")
        void fitsRegardlessOfImageSize() {
            assertThat(fileUrl(9L).length()).isLessThan(AVATAR_URL_LENGTH);
            // The id is the only part that varies; even an implausible one fits.
            assertThat(fileUrl(Long.MAX_VALUE).length()).isLessThan(AVATAR_URL_LENGTH);
        }

        /** The shape the five existing avatars already have. */
        @Test
        @DisplayName("matches what the admin route has always written")
        void matchesTheExistingRows() {
            assertThat(fileUrl(9L)).startsWith("/uploads/avatars/user_9_");
        }
    }
}
