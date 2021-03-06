package org.wikipedia;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.wikipedia.activity.FragmentUtil;
import org.wikipedia.analytics.GalleryFunnel;
import org.wikipedia.analytics.IntentFunnel;
import org.wikipedia.analytics.LoginFunnel;
import org.wikipedia.feed.FeedFragment;
import org.wikipedia.feed.image.FeaturedImage;
import org.wikipedia.feed.image.FeaturedImageCard;
import org.wikipedia.feed.news.NewsItemCard;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.history.HistoryFragment;
import org.wikipedia.login.LoginActivity;
import org.wikipedia.navtab.NavTab;
import org.wikipedia.nearby.NearbyFragment;
import org.wikipedia.navtab.NavTabFragmentPagerAdapter;
import org.wikipedia.news.NewsActivity;
import org.wikipedia.page.ExclusiveBottomSheetPresenter;
import org.wikipedia.page.PageActivity;
import org.wikipedia.page.PageTitle;
import org.wikipedia.page.gallery.GalleryActivity;
import org.wikipedia.page.gallery.ImagePipelineBitmapGetter;
import org.wikipedia.page.gallery.MediaDownloadReceiver;
import org.wikipedia.page.linkpreview.LinkPreviewDialog;
import org.wikipedia.readinglist.AddToReadingListDialog;
import org.wikipedia.readinglist.ReadingListsFragment;
import org.wikipedia.search.SearchFragment;
import org.wikipedia.search.SearchResultsFragment;
import org.wikipedia.settings.Prefs;
import org.wikipedia.settings.SettingsActivity;
import org.wikipedia.util.ClipboardUtil;
import org.wikipedia.util.DateUtil;
import org.wikipedia.util.FeedbackUtil;
import org.wikipedia.util.PermissionUtil;
import org.wikipedia.util.ShareUtil;
import org.wikipedia.util.UriUtil;
import org.wikipedia.util.log.L;
import org.wikipedia.views.ExploreOverflowView;

import java.io.File;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnPageChange;
import butterknife.Unbinder;

public class MainFragment extends Fragment implements BackPressedHandler, FeedFragment.Callback,
        NearbyFragment.Callback, HistoryFragment.Callback, ReadingListsFragment.Callback,
        SearchFragment.Callback, SearchResultsFragment.Callback,
        LinkPreviewDialog.Callback {
    @BindView(R.id.fragment_main_view_pager) ViewPager viewPager;
    @BindView(R.id.view_nav_view_pager_tab_layout) TabLayout tabLayout;
    private Unbinder unbinder;
    private SearchFragment searchFragment;
    private ExclusiveBottomSheetPresenter bottomSheetPresenter;
    private OverflowCallback overflowCallback = new OverflowCallback();

    // The permissions request API doesn't take a callback, so in the event we have to
    // ask for permission to download a featured image from the feed, we'll have to hold
    // the image we're waiting for permission to download as a bit of state here. :(
    @Nullable private FeaturedImage pendingDownloadImage;

    public interface Callback {
        void onTabChanged(@NonNull NavTab tab);
        void onSearchOpen();
        void onSearchClose(boolean shouldFinishActivity);
        @Nullable View getOverflowMenuButton();
    }

    public static MainFragment newInstance() {
        MainFragment fragment = new MainFragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    @Nullable @Override public View onCreateView(LayoutInflater inflater,
                                                 @Nullable ViewGroup container,
                                                 @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.fragment_main, container, false);
        unbinder = ButterKnife.bind(this, view);

        viewPager.setAdapter(new NavTabFragmentPagerAdapter(getChildFragmentManager()));
        tabLayout.setupWithViewPager(viewPager);

        bottomSheetPresenter = new ExclusiveBottomSheetPresenter(getChildFragmentManager());
        searchFragment = (SearchFragment) getChildFragmentManager().findFragmentById(R.id.search_fragment);
        searchFragment.setStatusBarVisible(false);

        if (savedInstanceState == null) {
            handleIntent(getActivity().getIntent());
        }
        return view;
    }

    @Override public void onResume() {
        super.onResume();
        // update toolbar, since Tab count might have changed
        getActivity().supportInvalidateOptionsMenu();
        // reset the last-page-viewed timer
        Prefs.pageLastShown(0);
    }

    @Override public void onDestroyView() {
        unbinder.unbind();
        unbinder = null;
        super.onDestroyView();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == Constants.ACTIVITY_REQUEST_VOICE_SEARCH
                && resultCode == Activity.RESULT_OK && data != null
                && data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) != null) {
            String searchQuery = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).get(0);
            openSearchFromIntent(searchQuery, SearchFragment.InvokeSource.VOICE);
        } else if (requestCode == Constants.ACTIVITY_REQUEST_GALLERY
                && resultCode == GalleryActivity.ACTIVITY_RESULT_FILEPAGE_SELECT) {
            startActivity(data);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_main, menu);
        viewPager.post(new Runnable() {
            @Override
            public void run() {
                setUpOverflowButton();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.main_menu_overflow:
                Callback callback = callback();
                if (callback == null) {
                    return false;
                }
                View overflowButton = callback.getOverflowMenuButton();
                if (overflowButton != null) {
                    showOverflowMenu(overflowButton);
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case Constants.ACTIVITY_REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION:
                if (PermissionUtil.isPermitted(grantResults)) {
                    if (pendingDownloadImage != null) {
                        download(pendingDownloadImage);
                    }
                } else {
                    setPendingDownload(null);
                    L.i("Write permission was denied by user");
                    FeedbackUtil.showMessage(this,
                            R.string.gallery_save_image_write_permission_rationale);
                }
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    public void handleIntent(Intent intent) {
        IntentFunnel funnel = new IntentFunnel(WikipediaApp.getInstance());
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && Constants.PLAIN_TEXT_MIME_TYPE.equals(intent.getType())) {
            funnel.logShareIntent();
            openSearchFromIntent(intent.getStringExtra(Intent.EXTRA_TEXT),
                    SearchFragment.InvokeSource.INTENT_SHARE);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())
                && Constants.PLAIN_TEXT_MIME_TYPE.equals(intent.getType())
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            funnel.logProcessTextIntent();
            openSearchFromIntent(intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT),
                    SearchFragment.InvokeSource.INTENT_PROCESS_TEXT);
        } else if (intent.hasExtra(Constants.INTENT_SEARCH_FROM_WIDGET)) {
            funnel.logSearchWidgetTap();
            openSearchFromIntent(null, SearchFragment.InvokeSource.WIDGET);
        } else if (lastPageViewedWithin(1)) {
            startActivity(PageActivity.newIntent(getContext()));
        }
    }

    private void setUpOverflowButton() {
        Callback callback = callback();
        if (callback == null) {
            return;
        }
        View overflowButton = callback.getOverflowMenuButton();
        if (overflowButton != null) {
            overflowButton.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    showOverflowMenu(view);
                    return true;
                }
            });
        }
    }

    @Override
    public void onFeedTabListRequested() {
        startActivity(PageActivity.newIntentForTabList(getContext()));
    }

    @Override public void onFeedSearchRequested() {
        searchFragment.setInvokeSource(SearchFragment.InvokeSource.FEED_BAR);
        searchFragment.openSearch();
    }

    @Override public void onFeedVoiceSearchRequested() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        try {
            startActivityForResult(intent, Constants.ACTIVITY_REQUEST_VOICE_SEARCH);
        } catch (ActivityNotFoundException a) {
            FeedbackUtil.showMessage(this, R.string.error_voice_search_not_available);
        }
    }

    @Override public void onFeedSelectPage(HistoryEntry entry) {
        startActivity(PageActivity.newIntent(getContext(), entry, entry.getTitle(), false));
    }

    @Override public void onFeedAddPageToList(HistoryEntry entry) {
        FeedbackUtil.showAddToListDialog(entry.getTitle(), AddToReadingListDialog.InvokeSource.FEED,
                bottomSheetPresenter, null);
    }

    @Override public void onFeedSharePage(HistoryEntry entry) {
        ShareUtil.shareText(getContext(), entry.getTitle());
    }

    @Override public void onFeedNewsItemSelected(NewsItemCard card) {
        startActivity(NewsActivity.newIntent(getContext(), card.item(), card.site()));
    }

    @Override public void onFeedShareImage(final FeaturedImageCard card) {
        final String thumbUrl = card.baseImage().thumbnail().source().toString();
        final String fullSizeUrl = card.baseImage().image().source().toString();
        new ImagePipelineBitmapGetter(getContext(), thumbUrl) {
            @Override
            public void onSuccess(@Nullable Bitmap bitmap) {
                if (bitmap != null) {
                    ShareUtil.shareImage(getContext(),
                            bitmap,
                            new File(thumbUrl).getName(),
                            getString(R.string.feed_featured_image_share_subject) + " | "
                                    + DateUtil.getFeedCardDateString(card.date().baseCalendar()),
                            fullSizeUrl);
                } else {
                    FeedbackUtil.showMessage(MainFragment.this, getString(R.string.gallery_share_error, card.baseImage().title()));
                }
            }
        }.get();
    }

    @Override public void onFeedDownloadImage(FeaturedImage image) {
        if (!(PermissionUtil.hasWriteExternalStoragePermission(getContext()))) {
            setPendingDownload(image);
            requestWriteExternalStoragePermission();
        } else {
            download(image);
        }
    }

    @Override public void onFeaturedImageSelected(FeaturedImageCard card) {
        startActivityForResult(GalleryActivity.newIntent(getActivity(), card.baseImage(),
                card.filename(), card.site(), GalleryFunnel.SOURCE_FEED_FEATURED_IMAGE),
                Constants.ACTIVITY_REQUEST_GALLERY);
    }

    @Override public void onLoading() {
        // todo: [overhaul] update loading indicator.
    }

    @Override public void onLoaded() {
        // todo: [overhaul] update loading indicator.
    }

    @Override public void onLoadPage(PageTitle title, int entrySource, @Nullable Location location) {
        showLinkPreview(title, entrySource, location);
    }

    @Override public void onLoadPage(PageTitle title, HistoryEntry entry) {
        startActivity(PageActivity.newIntent(getContext(), entry, entry.getTitle(), false));
    }

    @Override public void onClearHistory() {
        // todo: [overhaul] clear history.
    }

    @Override
    public void onSearchResultCopyLink(@NonNull PageTitle title) {
        copyLink(title.getCanonicalUri());
    }


    @Override
    public void onSearchResultAddToList(@NonNull PageTitle title, @NonNull AddToReadingListDialog.InvokeSource source) {
        FeedbackUtil.showAddToListDialog(title, source, bottomSheetPresenter, null);
    }

    @Override
    public void onSearchResultShareLink(@NonNull PageTitle title) {
        ShareUtil.shareText(getContext(), title);
    }

    @Override
    public void onSearchProgressBar(boolean enabled) {
        // TODO: implement
    }

    @Override
    public void onSearchSelectPage(@NonNull HistoryEntry entry, boolean inNewTab) {
        startActivity(PageActivity.newIntent(getContext(), entry, entry.getTitle(), inNewTab));
    }

    @Override
    public void onSearchOpen() {
        Callback callback = callback();
        if (callback != null) {
            callback.onSearchOpen();
        }
    }

    @Override
    public void onSearchClose() {
        Callback callback = callback();
        if (callback != null) {
            callback.onSearchClose(searchFragment.isLaunchedFromIntent());
        }
    }

    @Override
    public void onLinkPreviewLoadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry, boolean inNewTab) {
        startActivity(PageActivity.newIntent(getContext(), entry, entry.getTitle(), inNewTab));
    }

    @Override
    public void onLinkPreviewCopyLink(@NonNull PageTitle title) {
        copyLink(title.getCanonicalUri());
    }

    @Override
    public void onLinkPreviewAddToList(@NonNull PageTitle title) {
        FeedbackUtil.showAddToListDialog(title, AddToReadingListDialog.InvokeSource.LINK_PREVIEW_MENU, bottomSheetPresenter, null);
    }

    @Override
    public void onLinkPreviewShareLink(@NonNull PageTitle title) {
        ShareUtil.shareText(getContext(), title);
    }

    @Override
    public boolean onBackPressed() {
        Fragment fragment = ((NavTabFragmentPagerAdapter) viewPager.getAdapter()).getCurrentFragment();
        if (searchFragment.onBackPressed()) {
            return true;
        } else if (fragment instanceof BackPressedHandler && ((BackPressedHandler) fragment).onBackPressed()) {
            return true;
        }
        return false;
    }

    @OnPageChange(R.id.fragment_main_view_pager) void onTabChanged(int position) {
        Callback callback = callback();
        if (callback != null) {
            NavTab tab = NavTab.of(position);
            callback.onTabChanged(tab);
        }
    }

    private void showLinkPreview(PageTitle title, int entrySource, @Nullable Location location) {
        bottomSheetPresenter.show(LinkPreviewDialog.newInstance(title, entrySource, location));
    }

    private void copyLink(@NonNull String url) {
        ClipboardUtil.setPlainText(getContext(), null, url);
        FeedbackUtil.showMessage(this, R.string.address_copied);
    }

    public void openSearchFromIntent(@Nullable final String query,
                                      final SearchFragment.InvokeSource source) {
        tabLayout.post(new Runnable() {
            @Override
            public void run() {
                searchFragment.setInvokeSource(source);
                searchFragment.openSearch();
                if (query != null) {
                    searchFragment.setSearchText(query.trim());
                }
            }
        });
    }

    private boolean lastPageViewedWithin(int days) {
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - Prefs.pageLastShown()) < days;
    }

    private void download(@NonNull FeaturedImage image) {
        setPendingDownload(null);
        new MediaDownloadReceiver(getActivity()).download(image);
    }

    private void setPendingDownload(@Nullable FeaturedImage image) {
        pendingDownloadImage = image;
    }

    private void requestWriteExternalStoragePermission() {
        PermissionUtil.requestWriteStorageRuntimePermissions(this,
                Constants.ACTIVITY_REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION);
    }

    private void showOverflowMenu(@NonNull View anchor) {
        ExploreOverflowView overflowView = new ExploreOverflowView(getContext());
        overflowView.show(anchor, overflowCallback);
    }

    private class OverflowCallback implements ExploreOverflowView.Callback {
        @Override
        public void loginClick() {
            startActivityForResult(LoginActivity.newIntent(getContext(), LoginFunnel.SOURCE_NAV),
                    LoginActivity.REQUEST_LOGIN);
        }

        @Override
        public void settingsClick() {
            startActivityForResult(SettingsActivity.newIntent(getContext()),
                    SettingsActivity.ACTIVITY_REQUEST_SHOW_SETTINGS);
        }

        @Override
        public void donateClick() {
            UriUtil.visitInExternalBrowser(getContext(),
                    Uri.parse(String.format(getString(R.string.donate_url),
                            BuildConfig.VERSION_NAME,
                            WikipediaApp.getInstance().getSystemLanguageCode())));
        }

        @Override
        public void logoutClick() {
            WikipediaApp.getInstance().logOut();
            FeedbackUtil.showMessage(MainFragment.this, R.string.toast_logout_complete);
        }
    }

    @Nullable private Callback callback() {
        return FragmentUtil.getCallback(this, Callback.class);
    }
}