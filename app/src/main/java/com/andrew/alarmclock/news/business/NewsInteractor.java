package com.andrew.alarmclock.news.business;

import com.andrew.alarmclock.data.entities.Feed;
import com.andrew.alarmclock.data.entities.NewsItem;
import com.andrew.alarmclock.data.entities.api.WeatherAndNewsResponse;
import com.andrew.alarmclock.data.entities.api.news.NewsResponse;
import com.andrew.alarmclock.data.entities.api.weather.WeatherResponse;
import com.andrew.alarmclock.data.repository.feed.IFeedRepository;
import com.andrew.alarmclock.data.repository.location.ILocationRepository;
import com.andrew.alarmclock.data.repository.news.INewsRepository;
import com.andrew.alarmclock.data.repository.sharedPreferences.ISharedPreferencesRepository;
import com.andrew.alarmclock.data.repository.weather.IWeatherRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.Single;

public class NewsInteractor implements INewsInteractor {

    private IWeatherRepository weatherRepository;
    private INewsRepository newsRepository;
    private ILocationRepository locationRepository;
    private IFeedRepository feedRepository;
    private ISharedPreferencesRepository sharedPreferencesRepository;

    @Inject
    public NewsInteractor(IWeatherRepository weatherRepository,
                          INewsRepository newsRepository,
                          ILocationRepository locationRepository,
                          IFeedRepository feedRepository,
                          ISharedPreferencesRepository sharedPreferencesRepository) {
        this.weatherRepository = weatherRepository;
        this.newsRepository = newsRepository;
        this.locationRepository = locationRepository;
        this.feedRepository = feedRepository;
        this.sharedPreferencesRepository = sharedPreferencesRepository;
    }

    @Override
    public Single<Boolean> isDarkTheme() {
        return sharedPreferencesRepository.isDarkTheme();
    }

    @Override
    public Observable<WeatherResponse> getWeather() {
        return locationRepository.getLocation()
                .flatMap(value -> weatherRepository.getWeather(value));
    }

    @Override
    public Observable<NewsResponse> getNews() {
        return feedRepository.getAllFeeds()
                .toObservable()
                .flatMap(urls -> {
                    List<Observable<NewsResponse>> newsItemRequests = new ArrayList<>();
                    for (Feed feed : urls) {
                        newsItemRequests.add(newsRepository.getRssFeed(feed.getUrl())
                                .toList()
                                .map(NewsResponse::new)
                                .onErrorReturn(NewsResponse::new)
                                .toObservable());
                    }
                    return Observable.merge(newsItemRequests);
                })
                .toList()
                .toObservable()
                .flatMap(list -> {
                    int count = 0;
                    Set<Throwable> errors = new HashSet<>();
                    List<NewsItem> newsItems = new ArrayList<>();
                    for (NewsResponse response : list) {
                        if (response.getErrors().size() != 0) {
                            count++;
                            errors.addAll(response.getErrors());
                        }
                        if (response.getNewsItems().size() != 0) {
                            newsItems.addAll(response.getNewsItems());
                        }
                    }
                    if (count == list.size()) {
                        return newsRepository.getCachedRssFeed()
                                .map(val -> new NewsResponse(val, errors));
                    } else {
                        return newsRepository.refreshNewsDatabase(newsItems);
                    }
                });
    }

    @Override
    public Observable<WeatherAndNewsResponse> getWeatherAndNews() {
        return Observable.mergeDelayError(Arrays.asList(getNews(), getWeather()))
                .toList()
                .toObservable()
                .map(val -> {
                    WeatherAndNewsResponse response = new WeatherAndNewsResponse();
                    for(Object obj : val) {
                        if(obj instanceof NewsResponse) {
                            response.setNewsResponse((NewsResponse) obj);
                        }
                        if(obj instanceof WeatherResponse) {
                            response.setWeatherResponse((WeatherResponse) obj);
                        }
                    }
                    return response;
                });
    }
}
