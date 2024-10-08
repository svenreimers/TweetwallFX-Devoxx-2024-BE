/*
 * MIT License
 *
 * Copyright (c) 2024 TweetWallFX
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.tweetwallfx.conference.impl;

import static org.tweetwallfx.util.Nullable.nullable;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import javafx.scene.image.Image;

import org.tweetwallfx.cache.URLContent;
import org.tweetwallfx.cache.URLContentCacheBase;
import org.tweetwallfx.conference.spi.util.RestCallHelper;
import org.tweetwallfx.config.Configuration;
import org.tweetwallfx.stepengine.api.DataProvider;
import org.tweetwallfx.stepengine.api.config.StepEngineSettings;

public class DevoxxPhotoSharingDataProvider implements DataProvider, DataProvider.Scheduled {

    private static final Comparator<SharedPhoto> SHARED_PHOTO_COMPARATOR = Comparator
            .comparing(SharedPhoto::createdAt)
            .thenComparing(SharedPhoto::id);
    private final NavigableSet<SharedPhoto> sharedPhotos = new TreeSet<>(SHARED_PHOTO_COMPARATOR);
    private final Config config;
    private boolean initialized = false;

    private DevoxxPhotoSharingDataProvider(final Config config) {
        this.config = config;
    }

    public List<Image> getPhotos(final int amount) {
        return sharedPhotos.stream()
                .limit(amount)
                .map(SharedPhoto::url)
                .map(URLContentCacheBase.getDefault()::getCachedOrLoad)
                .map(URLContent::getInputStream)
                .map(Image::new)
                .toList();
    }

    @Override
    public ScheduledConfig getScheduleConfig() {
        return config;
    }

    @Override
    public boolean requiresInitialization() {
        return true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void run() {
        final Set<SharedPhoto> loadedPhotos = loadPhotos();

        // add loaded images to cache
        sharedPhotos.addAll(loadedPhotos);

        // ensure cache is sized correctly (from being too large)
        while (sharedPhotos.size() > config.cacheSize()) {
            sharedPhotos.removeLast();
        }

        // set to initialized
        initialized = true;
    }

    private Set<SharedPhoto> loadPhotos() {
        final String newestKnowId = Optional.of(sharedPhotos)
                .filter(Predicate.not(Set::isEmpty))
                .map(NavigableSet::first)
                .map(SharedPhoto::id)
                .orElse(null);

        Optional<PageInfo> pageInfo = Optional.empty();
        final Set<SharedPhoto> newPhotos = new TreeSet<>(SHARED_PHOTO_COMPARATOR);

        do {
            final Optional<SharedPhotos> requestedData = loadPhotosPage(pageInfo.map(PageInfo::lastVisible).orElse(null));
            pageInfo = requestedData.map(SharedPhotos::pageInfo);

            final Optional<List<SharedPhoto>> opSharedPhotos = requestedData.map(SharedPhotos::photos);
            opSharedPhotos.ifPresent(newPhotos::addAll);
            opSharedPhotos.ifPresent(this::triggerPhotoLoad);
        } while (pageInfo.map(PageInfo::hasMore).orElse(Boolean.FALSE)
                && newPhotos.size() < config.cacheSize()
                && (null == newestKnowId ^ newPhotos.stream().map(SharedPhoto::id).anyMatch(Predicate.isEqual(newestKnowId))));

        return newPhotos;
    }

    private void triggerPhotoLoad(final List<SharedPhoto> sharedPhotos) {
        final URLContentCacheBase cacheBase = URLContentCacheBase.getDefault();
        sharedPhotos.stream()
                .map(SharedPhoto::url)
                // process only those that are not yet cached
                .filter(Predicate.not(cacheBase::hasCachedContent))
                // add url of photo to be loaded
                .forEach(cacheBase::putCachedContent);
    }

    private Optional<SharedPhotos> loadPhotosPage(final String lastVisible) {
        return RestCallHelper.readOptionalFrom(
                config.queryUrl(),
                Configuration.mergeMap(
                        Map.of("pageSize", config.pageSize()),
                        null == lastVisible ? Map.of() : Map.of("lastVisible", lastVisible)),
                SharedPhotos.class);
    }

    /**
     * Implementation of {@link DataProvider.Factory} as Service implementation
     * creating {@link DevoxxPhotoSharingDataProvider}.
     */
    public static class FactoryImpl implements DataProvider.Factory {

        @Override
        public DevoxxPhotoSharingDataProvider create(final StepEngineSettings.DataProviderSetting dataProviderSetting) {
            return new DevoxxPhotoSharingDataProvider(dataProviderSetting.getConfig(Config.class));
        }

        @Override
        public Class<DevoxxPhotoSharingDataProvider> getDataProviderClass() {
            return DevoxxPhotoSharingDataProvider.class;
        }
    }

    /**
     * POJO used to configure {@link DevoxxPhotoSharingDataProvider}.
     *
     * <p>
     * Param {@code queryUrl} URL String where the shared photos can be queried
     *
     * <p>
     * Param {@code initialDelay} The type of scheduling to perform. Defaults to
     * {@link ScheduleType#FIXED_RATE}.
     *
     * <p>
     * Param {@code initialDelay} Delay until the first execution in seconds.
     * Defaults to {@code 0L}.
     *
     * <p>
     * Param {@code scheduleDuration} Fixed rate of / delay between consecutive
     * executions in seconds. Defaults to {@code 1800L}.
     */
    public static record Config(
            String queryUrl,
            Integer pageSize,
            Integer cacheSize,
            ScheduleType scheduleType,
            Long initialDelay,
            Long scheduleDuration) implements ScheduledConfig {

        @SuppressWarnings("unused")
        public Config(
                final String queryUrl,
                final Integer pageSize,
                final Integer cacheSize,
                final ScheduleType scheduleType,
                final Long initialDelay,
                final Long scheduleDuration) {
            this.queryUrl = queryUrl;
            this.pageSize = Objects.requireNonNullElse(pageSize, 10);
            if (this.pageSize <= 0) {
                throw new IllegalArgumentException("property 'pageSize' must be larger than zero");
            }
            this.cacheSize = Objects.requireNonNullElse(cacheSize, 100);
            if (this.cacheSize <= 0) {
                throw new IllegalArgumentException("property 'cacheSize' must be larger than zero");
            }
            // for ScheduledConfig
            this.scheduleType = Objects.requireNonNullElse(scheduleType, ScheduleType.FIXED_RATE);
            this.initialDelay = Objects.requireNonNullElse(initialDelay, 0L);
            this.scheduleDuration = Objects.requireNonNullElse(scheduleDuration, 30 * 60L);
        }
    }

    public static record SharedPhotos(
            List<SharedPhoto> photos,
            PageInfo pageInfo) {

        public SharedPhotos(
                final List<SharedPhoto> photos,
                final PageInfo pageInfo) {
            this.photos = nullable(photos);
            this.pageInfo = pageInfo;
        }

        @Override
        public List<SharedPhoto> photos() {
            return nullable(photos);
        }
    }

    public static record SharedPhoto(
            Instant createdAt,
            long likes,
            boolean flaggedAsSpam,
            String id,
            String url) {
    }

    public static record PageInfo(
            long pageSize,
            String lastVisible,
            boolean hasMore) {
    }
}
