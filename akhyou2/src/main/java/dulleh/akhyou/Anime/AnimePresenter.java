package dulleh.akhyou.Anime;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.graphics.Palette;

import java.util.List;

import de.greenrobot.event.EventBus;
import dulleh.akhyou.Models.AnimeProviders.AnimeRushAnimeProvider;
import dulleh.akhyou.Models.AnimeProviders.AnimeProvider;
import dulleh.akhyou.MainActivity;
import dulleh.akhyou.Models.Anime;
import dulleh.akhyou.Models.Source;
import dulleh.akhyou.Models.Video;
import dulleh.akhyou.R;
import dulleh.akhyou.Utils.Events.FavouriteEvent;
import dulleh.akhyou.Utils.Events.LastAnimeEvent;
import dulleh.akhyou.Utils.Events.OpenAnimeEvent;
import dulleh.akhyou.Utils.Events.SnackbarEvent;
import dulleh.akhyou.Utils.GeneralUtils;
import nucleus.presenter.RxPresenter;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func0;
import rx.schedulers.Schedulers;

public class AnimePresenter extends RxPresenter<AnimeFragment>{
    private static final String LAST_ANIME_BUNDLE_KEY = "last_anime";

    private Subscription animeSubscription;
    private Subscription episodeSubscription;
    private Subscription videoSubscription;
    private AnimeProvider animeProvider;

    private Anime lastAnime;
    public boolean isRefreshing;
    private static boolean needToGiveFavouriteState = false;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        if (animeProvider == null) {
            animeProvider = new AnimeRushAnimeProvider();
        }

        // subscribe here (rather than in onTakeView() so that we don't receive
        // a stickied event every time the motherfucker takes the view.
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().registerSticky(this);
        }

        if (savedState != null) {
            lastAnime = savedState.getParcelable(LAST_ANIME_BUNDLE_KEY);
        }

    }

    @Override
    protected void onTakeView(AnimeFragment view) {
        super.onTakeView(view);
        view.updateRefreshing();
        if (lastAnime != null) {
            if (lastAnime.getEpisodes() != null) {
                view.setAnime(lastAnime, isInFavourites());
            } else if (lastAnime.getTitle() != null) {
                view.setToolbarTitle(lastAnime.getTitle());
            }
        }
        if (needToGiveFavouriteState) {
            view.setFavouriteChecked(isInFavourites());
            needToGiveFavouriteState = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        animeProvider = null;
        unsubscribe();
    }

    @Override
    protected void onSave(Bundle state) {
        super.onSave(state);
        state.putParcelable(LAST_ANIME_BUNDLE_KEY, lastAnime);
    }

    private void unsubscribe () {
        if (animeSubscription != null && !animeSubscription.isUnsubscribed()) {
            animeSubscription.unsubscribe();
        }
        if (episodeSubscription != null && !episodeSubscription.isUnsubscribed()) {
            episodeSubscription.unsubscribe();
        }
        if (videoSubscription != null && !videoSubscription.isUnsubscribed()) {
            videoSubscription.unsubscribe();
        }
    }

    public void onEvent (OpenAnimeEvent event) {
        lastAnime = event.anime;
        if (lastAnime != null && lastAnime.getEpisodes() != null) {
            getView().setAnime(lastAnime, isInFavourites());
            fetchAnime(true);
        } else {
            fetchAnime(false);
        }
    }

    public void fetchAnime (boolean updateCached) {
        isRefreshing = true;
        if (getView() != null) {
            getView().updateRefreshing();
        }

        if (animeSubscription != null) {
            if (!animeSubscription.isUnsubscribed()) {
                animeSubscription.unsubscribe();
            }
        }

        animeSubscription = Observable.defer(new Func0<Observable<Anime>>() {
            @Override
            public Observable<Anime> call() {
                if (updateCached) {
                    return Observable.just(animeProvider.updateCachedAnime(lastAnime));
                }
                return Observable.just(animeProvider.fetchAnime(lastAnime.getUrl()));
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(this.deliverLatestCache())
                .subscribe(new Subscriber<Anime>() {
                    @Override
                    public void onNext(Anime anime) {
                        lastAnime = anime;
                        if (getView() != null) {
                            getView().setAnime(lastAnime, isInFavourites());
                        }
                        EventBus.getDefault().post(new LastAnimeEvent(lastAnime));
                    }

                    @Override
                    public void onCompleted() {
                        isRefreshing = false;
                        animeSubscription.unsubscribe();
                        this.unsubscribe();
                    }

                    @Override
                    public void onError(Throwable e) {
                        isRefreshing = false;
                        getView().updateRefreshing();
                        postError(e);
                        this.unsubscribe();
                    }

                });
    }

    public Boolean isInFavourites() {
        if (getView() != null) {
            try {
                //TODO: THIS IS TERRIBLE. >>> FIND A BETTER WAY
                // PLEASE TELL ME THERE'S A BETTER WAY ;-;
                return ((MainActivity) getView().getActivity()).getPresenter().getModel().isInFavourites(lastAnime.getUrl());
            } catch (Exception e) {
                postError(e);
            }
        }
        return null;
    }

    public void setNeedToGiveFavourite (boolean bool) {
        needToGiveFavouriteState = bool;
    }

    public void setMajorColour (int colour) {
        lastAnime.setMajorColour(colour);
    }

    public void setMajorColour (Palette palette) {
        if (palette != null) {
            if (palette.getVibrantSwatch() != null) {
                lastAnime.setMajorColour(palette.getVibrantSwatch().getRgb());
            } else if (palette.getLightVibrantSwatch() != null) {
                lastAnime.setMajorColour(palette.getLightVibrantSwatch().getRgb());
            } else if (palette.getDarkMutedSwatch() != null) {
                lastAnime.setMajorColour(palette.getDarkMutedSwatch().getRgb());
            }
        } else {
            lastAnime.setMajorColour(getView().getResources().getColor(R.color.accent));
        }
    }

    public void onFavouriteCheckedChanged (boolean b) {
        EventBus.getDefault().post(new FavouriteEvent(b, lastAnime));
    }

    public void downloadOrStream (Video video, boolean download) {
        if (download) {
            DownloadManager downloadManager = (DownloadManager) getView().getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(video.getUrl()));
            request.setTitle(video.getTitle());
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            downloadManager.enqueue(request);
            // NEED TO USE BROADCAST RECEIVERS TO HANDLE CLICKS ON THE NOTIFICATION
        } else {
            postIntent(video.getUrl());
        }
    }

    private void postIntent (String videoUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(videoUrl), "video/*");
        if (intent.resolveActivity(getView().getActivity().getPackageManager()) != null) {
            getView().startActivity(intent);
        }
    }

    public void flipWatched (int position) {
        lastAnime.getEpisodes().get(position).flipWatched();
        getView().notifyAdapter();
    }

    public void fetchSources (String url) {
        if (episodeSubscription != null) {
            if (!episodeSubscription.isUnsubscribed()) {
                episodeSubscription.unsubscribe();
            }
        }
        
        episodeSubscription = Observable.defer(new Func0<Observable<List<Source>>>() {
            @Override
            public Observable<List<Source>> call() {
                return Observable.just(animeProvider.fetchSources(url));
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(this.deliver())
                .subscribe(new Subscriber<List<Source>>() {
                    @Override
                    public void onNext(List<Source> sources) {
                        if (getView() != null) {
                            getView().showSourcesDialog(sources);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        episodeSubscription.unsubscribe();
                    }

                    @Override
                    public void onError(Throwable e) {
                        postError(e);
                        this.unsubscribe();
                    }

                });
    }

    public void fetchVideo (Source source, boolean download) {
        if (videoSubscription != null) {
            if (!videoSubscription.isUnsubscribed()) {
                videoSubscription.unsubscribe();
            }
        }

        videoSubscription = Observable.defer(new Func0<Observable<Source>>() {
            @Override
            public Observable<Source> call() {
                return Observable.just(animeProvider.fetchVideo(source));
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(this.deliver())
                .subscribe(new Subscriber<Source>() {
                    @Override
                    public void onNext(Source source) {
                        if (getView() != null) {
                            getView().shareVideo(source, download);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        this.unsubscribe();
                    }

                    @Override
                    public void onError(Throwable e) {
                        postError(e);
                        this.unsubscribe();
                    }

                });
    }

    public void postError (Throwable e) {
        e.printStackTrace();
        EventBus.getDefault().post(new SnackbarEvent(GeneralUtils.formatError(e)));
    }

    public void postSuccess (String successMessage) {
        EventBus.getDefault().post(new SnackbarEvent(successMessage));
    }

}
