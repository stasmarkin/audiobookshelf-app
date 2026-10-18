<template>
  <div class="w-full h-full px-4 py-8 overflow-y-auto">
    <!-- Display settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2">{{ $strings.HeaderUserInterfaceSettings }}</p>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleEnableAltView">
        <ui-toggle-switch v-model="enableBookshelfView" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelUseBookshelfView }}</p>
    </div>
    <!-- screen.orientation.lock not supported on iOS webview -->
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click.stop="toggleLockOrientation">
        <ui-toggle-switch v-model="lockCurrentOrientation" class="pointer-events-none" />
      </div>
      <p class="pl-4">{{ $strings.LabelLockOrientation }}</p>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelHapticFeedback }}</p>
      <div @click.stop="showHapticFeedbackOptions">
        <ui-text-input :value="hapticFeedbackOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelLanguage }}</p>
      <div @click.stop="showLanguageOptions">
        <ui-text-input :value="languageOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelTheme }}</p>
      <div @click.stop="showThemeOptions">
        <ui-text-input :value="themeOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <nuxt-link to="/appearance" class="py-3 flex items-center">
      <span class="material-symbols text-xl w-10 text-center" aria-hidden="true">palette</span>
      <span class="pl-4 flex-grow">
        <span class="block">{{ $strings.LabelRiceAppearanceSettings }}</span>
        <span class="block text-sm text-fg-muted">{{ $strings.MessageRiceAppearanceSubtitle }}</span>
      </span>
      <span class="material-symbols text-xl text-fg-muted" aria-hidden="true">chevron_right</span>
    </nuxt-link>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelPodcastEpisodesSort }}</p>
      <div @click.stop="showPodcastEpisodesOrderModal = true">
        <ui-text-input :value="podcastEpisodesSortText" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>

    <!-- Playback settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderPlaybackSettings }}</p>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelJumpBackwardsTime }}</p>
      <div @click.stop="showJumpBackwardsOptions">
        <ui-text-input :value="jumpBackwardsOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelJumpForwardsTime }}</p>
      <div @click.stop="showJumpForwardOptions">
        <ui-text-input :value="jumpForwardOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelLongJumpBackwardsTime }}</p>
      <div @click.stop="showLongJumpBackwardsOptions">
        <ui-text-input :value="longJumpBackwardsOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelLongJumpForwardsTime }}</p>
      <div @click.stop="showLongJumpForwardOptions">
        <ui-text-input :value="longJumpForwardOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleDisableAutoRewind">
        <ui-toggle-switch v-model="settings.disableAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelDisableAutoRewind }}</p>
    </div>
    <div v-if="!isiOS" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleEnableMp3IndexSeeking">
        <ui-toggle-switch v-model="settings.enableMp3IndexSeeking" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelEnableMp3IndexSeeking }}</p>
      <span class="material-symbols text-xl ml-2" @click.stop="showConfirmMp3IndexSeeking">info</span>
    </div>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleAllowSeekingOnMediaControls">
        <ui-toggle-switch v-model="settings.allowSeekingOnMediaControls" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelAllowSeekingOnMediaControls }}</p>
    </div>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleAskBeforeStreamingIncomplete">
        <ui-toggle-switch v-model="settings.askBeforeStreamingIncomplete" @input="saveSettings" />
      </div>
      <p class="pl-4">Ask before streaming incomplete series/collections/playlists</p>
    </div>

    <div v-if="isAndroid" class="py-3">
      <label for="external-media-navigation" class="block mb-2">{{ $strings.LabelExternalMediaControls }}</label>
      <select id="external-media-navigation" v-model="settings.externalMediaNavigation" class="w-full bg-bg text-fg border border-border rounded px-3 py-2" @change="saveSettings">
        <option value="time">{{ $strings.LabelExternalMediaTime }}</option>
        <option value="chapters">{{ $strings.LabelExternalMediaChapters }}</option>
        <option value="files">{{ $strings.LabelExternalMediaFiles }}</option>
      </select>
      <p class="text-sm text-fg-muted mt-2">{{ $strings.MessageExternalMediaControls }}</p>
    </div>

    <!-- Sleep timer settings -->
    <template v-if="!isiOS">
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderSleepTimerSettings }}</p>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleDisableShakeToResetSleepTimer">
          <ui-toggle-switch v-model="settings.disableShakeToResetSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableShakeToReset }}</p>
        <span class="material-symbols text-xl ml-2" @click.stop="showInfo('disableShakeToResetSleepTimer')">info</span>
      </div>
      <div v-if="!settings.disableShakeToResetSleepTimer" class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelShakeSensitivity }}</p>
        <div @click.stop="showShakeSensitivityOptions">
          <ui-text-input :value="shakeSensitivityOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
        </div>
      </div>
    </template>
    <div class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleDisableSleepTimerFadeOut">
        <ui-toggle-switch v-model="settings.disableSleepTimerFadeOut" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelDisableAudioFadeOut }}</p>
      <span class="material-symbols text-xl ml-2" @click.stop="showInfo('disableSleepTimerFadeOut')">info</span>
    </div>
    <template v-if="!isiOS">
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleDisableSleepTimerResetFeedback">
          <ui-toggle-switch v-model="settings.disableSleepTimerResetFeedback" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelDisableVibrateOnReset }}</p>
        <span class="material-symbols text-xl ml-2" @click.stop="showInfo('disableSleepTimerResetFeedback')">info</span>
      </div>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleSleepTimerAlmostDoneChime">
          <ui-toggle-switch v-model="settings.enableSleepTimerAlmostDoneChime" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelSleepTimerAlmostDoneChime }}</p>
        <span class="material-symbols text-xl ml-2" @click.stop="showInfo('enableSleepTimerAlmostDoneChime')">info</span>
      </div>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center" @click="toggleAutoSleepTimer">
          <ui-toggle-switch v-model="settings.autoSleepTimer" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelAutoSleepTimer }}</p>
        <span class="material-symbols text-xl ml-2" @click.stop="showInfo('autoSleepTimer')">info</span>
      </div>
    </template>
    <!-- Auto Sleep timer settings -->
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelStartTime }}</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerStartTime" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelEndTime }}</p>
      <ui-text-input type="time" v-model="settings.autoSleepTimerEndTime" style="width: 145px; max-width: 145px" @input="autoSleepTimerTimeUpdated" />
    </div>
    <div v-if="settings.autoSleepTimer" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelSleepTimer }}</p>
      <div @click.stop="showSleepTimerOptions">
        <ui-text-input :value="sleepTimerLengthOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>
    <div v-if="settings.autoSleepTimer" class="flex items-center py-3">
      <div class="w-10 flex justify-center" @click="toggleAutoSleepTimerAutoRewind">
        <ui-toggle-switch v-model="settings.autoSleepTimerAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">{{ $strings.LabelAutoSleepTimerAutoRewind }}</p>
      <span class="material-symbols text-xl ml-2" @click.stop="showInfo('autoSleepTimerAutoRewind')">info</span>
    </div>
    <div v-if="settings.autoSleepTimerAutoRewind" class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelAutoRewindTime }}</p>
      <div @click.stop="showAutoSleepTimerRewindOptions">
        <ui-text-input :value="autoSleepTimerRewindLengthOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
      </div>
    </div>

    <!-- Data settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderDataSettings }}</p>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelDownloadUsingCellular }}</p>
      <div @click.stop="showDownloadUsingCellularOptions">
        <ui-text-input :value="downloadUsingCellularOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <div class="py-3 flex items-center">
      <p class="pr-4 w-36">{{ $strings.LabelStreamingUsingCellular }}</p>
      <div @click.stop="showStreamingUsingCellularOptions">
        <ui-text-input :value="streamingUsingCellularOption" readonly append-icon="expand_more" style="max-width: 200px" />
      </div>
    </div>
    <template v-if="isAndroid">
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelDownloadStorage }}</p>
        <div @click.stop="showDownloadStorageOptions">
          <ui-text-input :value="downloadStorageOption" readonly append-icon="expand_more" style="max-width: 200px" />
        </div>
      </div>
      <p v-for="line in downloadStorageView.lines" :key="line" class="text-xs text-fg-muted pb-1">{{ line }}</p>
      <p v-if="downloadStorageView.sdSelectable || downloadStorageValue === 'sd'" class="text-xs text-fg-muted pb-1">{{ $strings.MessageDownloadStorageHint }}</p>
    </template>

    <template v-if="isAndroid">
      <!-- Auto delete and storage settings -->
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderAutoDeleteSettings }}</p>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center">
          <ui-toggle-switch v-model="settings.autoDeleteFinishedBooks" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelAutoDeleteBooks }}</p>
      </div>
      <p class="text-xs text-fg-muted pb-1">{{ $strings.MessageAutoDeleteHelp }}</p>
      <div v-if="settings.autoDeleteFinishedBooks" class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelAutoDeleteDelay }}</p>
        <div @click.stop="showAutoDeleteDelayOptions">
          <ui-text-input :value="autoDeleteDelayOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
        </div>
      </div>
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelDownloadsLimit }}</p>
        <div @click.stop="showDownloadsLimitOptions">
          <ui-text-input :value="downloadsLimitOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
        </div>
      </div>
      <p class="text-xs text-fg-muted pb-1">{{ $strings.MessageDownloadsLimitHelp }}</p>
      <p v-if="downloadsStorageText" class="text-xs text-fg-muted pt-1">{{ downloadsStorageText }}</p>
      <p v-if="downloadsStorage && downloadsStorage.skippedForLimit > 0 && settings.downloadsLimitGb > 0 && settings.autoDownloadEnabled" class="text-xs text-fg-muted pt-1">{{ $getString('LabelDownloadsSkippedForLimit', [downloadsStorage.skippedForLimit]) }}</p>
    </template>

    <!-- Podcast auto download settings -->
    <template v-if="isAndroid">
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderAutoDownloadSettings }}</p>
      <div class="flex items-center py-3">
        <div class="w-10 flex justify-center">
          <ui-toggle-switch v-model="settings.autoDownloadEnabled" @input="saveSettings" />
        </div>
        <p class="pl-4">{{ $strings.LabelAutoDownloadEpisodesToDevice }}</p>
      </div>
      <template v-if="settings.autoDownloadEnabled">
        <div class="flex items-center py-3">
          <div class="w-10 flex justify-center">
            <ui-toggle-switch v-model="settings.autoDownloadAllPodcasts" @input="saveSettings" />
          </div>
          <p class="pl-4">{{ $strings.LabelAutoDownloadAllPodcasts }}</p>
        </div>
        <div class="py-3 flex items-center">
          <p class="pr-4 w-36">{{ $strings.LabelAutoDownloadKeepCount }}</p>
          <div @click.stop="showAutoDownloadKeepCountOptions">
            <ui-text-input :value="autoDownloadKeepCountOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
          </div>
        </div>
        <div class="flex items-center py-3">
          <div class="w-10 flex justify-center">
            <ui-toggle-switch v-model="settings.autoDownloadWifiOnly" @input="saveSettings" />
          </div>
          <p class="pl-4">{{ $strings.LabelAutoDownloadOnlyWifi }}</p>
        </div>
        <div class="flex items-center py-3">
          <div class="w-10 flex justify-center">
            <ui-toggle-switch v-model="settings.autoDownloadChargingOnly" @input="saveSettings" />
          </div>
          <p class="pl-4">{{ $strings.LabelAutoDownloadOnlyCharging }}</p>
        </div>
        <div class="py-3 flex items-center">
          <p class="pr-4 w-36">{{ $strings.LabelAutoDownloadCheckInterval }}</p>
          <div @click.stop="showAutoDownloadIntervalOptions">
            <ui-text-input :value="autoDownloadIntervalOption" readonly append-icon="expand_more" style="width: 145px; max-width: 145px" />
          </div>
        </div>
      </template>
      <p class="text-xs text-fg-muted pt-1">{{ $strings.MessageAutoDownloadSettingsHelp }}</p>
    </template>

    <!-- Android Auto settings -->
    <template v-if="!isiOS">
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderAndroidAutoSettings }}</p>
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelAndroidAutoBrowseLimitForGrouping }}</p>
        <ui-text-input type="number" v-model="settings.androidAutoBrowseLimitForGrouping" style="width: 145px; max-width: 145px" @input="androidAutoBrowseLimitForGroupingUpdated" />
        <span class="material-symbols text-xl ml-2" @click.stop="showInfo('androidAutoBrowseLimitForGrouping')">info</span>
      </div>
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">{{ $strings.LabelAndroidAutoBrowseSeriesSequenceOrder }}</p>
        <div @click.stop="showAndroidAutoBrowseSeriesSequenceOrderOptions">
          <ui-text-input :value="androidAutoBrowseSeriesSequenceOrderOption" readonly append-icon="expand_more" style="max-width: 200px" />
        </div>
      </div>

      <!-- Diagnostics -->
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderDiagnostics }}</p>
      <!-- Wraps so the picker moves below the label at large font sizes instead of covering it -->
      <div class="py-3 flex flex-wrap items-center gap-y-2">
        <p class="pr-4 w-36 min-w-min">{{ $strings.LabelDiagnosticLogging }}</p>
        <div @click.stop="showDiagnosticLevelOptions">
          <ui-text-input :value="diagnosticLevelOption" readonly append-icon="expand_more" style="max-width: 200px" />
        </div>
      </div>
      <p class="text-xs text-fg-muted pb-2">{{ $strings.MessageDiagnosticLoggingHelp }}</p>
      <p v-if="diagnosticLogBytes !== null" class="text-xs text-fg-muted pb-3">{{ $strings.LabelDiagnosticLogSize }}: {{ $bytesPretty(diagnosticLogBytes) }}</p>
      <p class="text-sm font-semibold mb-2">{{ $strings.LabelLogActions }}</p>
      <!-- Wraps to more rows on narrow screens / large font sizes; labels never shrink or truncate -->
      <div class="flex flex-wrap gap-2 pb-4" role="group" :aria-label="$strings.LabelLogActions">
        <ui-btn v-for="action in diagnosticLogActions" :key="action.id" small :aria-label="action.ariaLabel" :loading="(action.id === 'share' && isSharingDiagnosticLog) || (action.id === 'save' && isSavingDiagnosticLog)" class="min-h-12" @click="action.handler">
          <span class="flex items-center whitespace-nowrap">
            <span class="material-symbols text-lg leading-none mr-1" aria-hidden="true">{{ action.icon }}</span>
            <span>{{ action.text }}</span>
          </span>
        </ui-btn>
      </div>

      <!-- In-app updates from the official GitHub releases (Android) -->
      <settings-updates />
    </template>

    <div v-show="loading" class="w-full h-full absolute top-0 left-0 flex items-center justify-center z-10">
      <ui-loading-indicator />
    </div>

    <modals-dialog v-model="showMoreMenuDialog" :items="moreMenuItems" :selected="moreMenuSelected" @action="clickMenuAction" />
    <modals-sleep-timer-length-modal v-model="showSleepTimerLengthModal" @change="sleepTimerLengthModalSelection" />
    <modals-auto-sleep-timer-rewind-length-modal v-model="showAutoSleepTimerRewindLengthModal" @change="showAutoSleepTimerRewindLengthModalSelection" />
    <modals-order-modal v-model="showPodcastEpisodesOrderModal" :order-by.sync="podcastEpisodesOrderBy" :descending.sync="podcastEpisodesOrderDesc" episodes allow-auto />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AUTO_DELETE_DELAY_DAYS, DOWNLOADS_LIMIT_GB, downloadsFreeBytes, normalizeDelayDays, normalizeLimitGb } from '@/utils/autoDeleteSettings'
import jumpLabelMixin from '@/mixins/jumpLabel'
import diagnosticLogMixin from '@/mixins/diagnosticLog'
import { AbsDatabase, AbsDownloader } from '@/plugins/capacitor'
import { storageStatusView } from '@/utils/downloadStorageView'
import { LONG_JUMP_SECONDS_OPTIONS, normalizeLongJumpTime } from '@/utils/longJump'

export default {
  mixins: [jumpLabelMixin, diagnosticLogMixin],
  data() {
    return {
      loading: false,
      deviceData: null,
      showMoreMenuDialog: false,
      showSleepTimerLengthModal: false,
      showAutoSleepTimerRewindLengthModal: false,
      showPodcastEpisodesOrderModal: false,
      moreMenuSetting: '',
      downloadStorageStatus: null,
      downloadStorageListener: null,
      settingDownloadStorage: false,
      settings: {
        disableAutoRewind: false,
        enableAltView: true,
        allowSeekingOnMediaControls: false,
        jumpForwardTime: 10,
        externalMediaNavigation: 'time',
        jumpBackwardsTime: 10,
        longJumpForwardTime: 0,
        longJumpBackwardsTime: 0,
        enableMp3IndexSeeking: false,
        disableShakeToResetSleepTimer: false,
        shakeSensitivity: 'MEDIUM',
        lockOrientation: 0,
        hapticFeedback: 'LIGHT',
        autoSleepTimer: false,
        autoSleepTimerStartTime: '22:00',
        autoSleepTimerEndTime: '06:00',
        sleepTimerLength: 900000, // 15 minutes
        disableSleepTimerFadeOut: false,
        disableSleepTimerResetFeedback: false,
        enableSleepTimerAlmostDoneChime: false,
        autoSleepTimerAutoRewind: false,
        autoSleepTimerAutoRewindTime: 300000, // 5 minutes
        languageCode: 'en-us',
        downloadUsingCellular: 'ALWAYS',
        streamingUsingCellular: 'ALWAYS',
        androidAutoBrowseLimitForGrouping: 100,
        androidAutoBrowseSeriesSequenceOrder: 'ASC',
        askBeforeStreamingIncomplete: false,
        autoDownloadEnabled: false,
        autoDownloadAllPodcasts: false,
        autoDownloadKeepCount: 3,
        autoDownloadWifiOnly: true,
        autoDownloadChargingOnly: false,
        autoDownloadIntervalHours: 6,
        autoDeleteFinishedBooks: false,
        autoDeleteDelayDays: 1,
        downloadsLimitGb: 0
      },
      downloadsStorage: null,
      theme: 'dark',
      lockCurrentOrientation: false,
      settingInfo: {
        disableShakeToResetSleepTimer: {
          name: this.$strings.LabelDisableShakeToReset,
          message: this.$strings.LabelDisableShakeToResetHelp
        },
        autoSleepTimer: {
          name: this.$strings.LabelAutoSleepTimer,
          message: this.$strings.LabelAutoSleepTimerHelp
        },
        disableSleepTimerFadeOut: {
          name: this.$strings.LabelDisableAudioFadeOut,
          message: this.$strings.LabelDisableAudioFadeOutHelp
        },
        disableSleepTimerResetFeedback: {
          name: this.$strings.LabelDisableVibrateOnReset,
          message: this.$strings.LabelDisableVibrateOnResetHelp
        },
        enableSleepTimerAlmostDoneChime: {
          name: this.$strings.LabelSleepTimerAlmostDoneChime,
          message: this.$strings.LabelSleepTimerAlmostDoneChimeHelp
        },
        autoSleepTimerAutoRewind: {
          name: this.$strings.LabelAutoSleepTimerAutoRewind,
          message: this.$strings.LabelAutoSleepTimerAutoRewindHelp
        },
        enableMp3IndexSeeking: {
          name: this.$strings.LabelEnableMp3IndexSeeking,
          message: this.$strings.LabelEnableMp3IndexSeekingHelp
        },
        androidAutoBrowseLimitForGrouping: {
          name: this.$strings.LabelAndroidAutoBrowseLimitForGrouping,
          message: this.$strings.LabelAndroidAutoBrowseLimitForGroupingHelp
        }
      },
      hapticFeedbackItems: [
        {
          text: this.$strings.LabelOff,
          value: 'OFF'
        },
        {
          text: this.$strings.LabelLight,
          value: 'LIGHT'
        },
        {
          text: this.$strings.LabelMedium,
          value: 'MEDIUM'
        },
        {
          text: this.$strings.LabelHeavy,
          value: 'HEAVY'
        }
      ],
      shakeSensitivityItems: [
        {
          text: this.$strings.LabelVeryLow,
          value: 'VERY_LOW'
        },
        {
          text: this.$strings.LabelLow,
          value: 'LOW'
        },
        {
          text: this.$strings.LabelMedium,
          value: 'MEDIUM'
        },
        {
          text: this.$strings.LabelHigh,
          value: 'HIGH'
        },
        {
          text: this.$strings.LabelVeryHigh,
          value: 'VERY_HIGH'
        }
      ],
      downloadUsingCellularItems: [
        {
          text: this.$strings.LabelAskConfirmation,
          value: 'ASK'
        },
        {
          text: this.$strings.LabelAlways,
          value: 'ALWAYS'
        },
        {
          text: this.$strings.LabelNever,
          value: 'NEVER'
        }
      ],
      streamingUsingCellularItems: [
        {
          text: this.$strings.LabelAskConfirmation,
          value: 'ASK'
        },
        {
          text: this.$strings.LabelAlways,
          value: 'ALWAYS'
        },
        {
          text: this.$strings.LabelNever,
          value: 'NEVER'
        }
      ],
      androidAutoBrowseSeriesSequenceOrderItems: [
        {
          text: this.$strings.LabelSequenceAscending,
          value: 'ASC'
        },
        {
          text: this.$strings.LabelSequenceDescending,
          value: 'DESC'
        }
      ]
    }
  },
  computed: {
    podcastEpisodesOrderBy: {
      get() {
        return this.$store.getters['user/getUserSetting']('podcastEpisodesOrderBy') || 'publishedAt'
      },
      set(val) {
        this.$store.dispatch('user/updateUserSettings', { podcastEpisodesOrderBy: val })
      }
    },
    podcastEpisodesOrderDesc: {
      get() {
        return this.$store.getters['user/getUserSetting']('podcastEpisodesOrderDesc')
      },
      set(val) {
        this.$store.dispatch('user/updateUserSettings', { podcastEpisodesOrderDesc: val })
      }
    },
    podcastEpisodesSortText() {
      const item = this.$constants.PodcastEpisodeSortItems.find((i) => i.value === this.podcastEpisodesOrderBy)
      const keyText = item ? this.$strings[item.labelKey] : ''
      const desc = this.podcastEpisodesOrderDesc
      const dirText = desc == null ? this.$strings.LabelAuto : desc ? this.$strings.LabelDescending : this.$strings.LabelAscending
      return `${keyText} · ${dirText}`
    },
    // This is flipped because alt view was the default until v0.9.61-beta
    enableBookshelfView: {
      get() {
        return !this.settings.enableAltView
      },
      set(val) {
        this.settings.enableAltView = !val
      }
    },
    isiOS() {
      return this.$platform === 'ios'
    },
    isAndroid() {
      return this.$platform === 'android'
    },
    autoDownloadKeepCountItems() {
      return [1, 2, 3, 5, 10, 15, 20, 30, 50, 100].map((n) => ({ text: String(n), value: n }))
    },
    autoDownloadIntervalItems() {
      return [1, 6, 12, 24].map((h) => ({ text: h === 1 ? this.$strings.LabelEveryHour : this.$getString('LabelEveryHours', [h]), value: h }))
    },
    autoDeleteDelayItems() {
      return AUTO_DELETE_DELAY_DAYS.map((d) => ({ text: d === 0 ? this.$strings.LabelAutoDeleteRightAway : d === 1 ? this.$strings.LabelOneDay : this.$getString('LabelDaysCount', [d]), value: d }))
    },
    downloadsLimitItems() {
      return DOWNLOADS_LIMIT_GB.map((gb) => ({ text: gb === 0 ? this.$strings.LabelOff : this.$getString('LabelDownloadsLimitGb', [gb]), value: gb }))
    },
    autoDeleteDelayOption() {
      return this.autoDeleteDelayItems.find((i) => i.value === this.settings.autoDeleteDelayDays)?.text || 'Error'
    },
    downloadsLimitOption() {
      return this.downloadsLimitItems.find((i) => i.value === this.settings.downloadsLimitGb)?.text || 'Error'
    },
    downloadsStorageText() {
      const info = this.downloadsStorage
      if (!info) return ''
      const used = this.$bytesPretty(info.usedBytes, 1)
      // Free space is that of the storage new downloads go to (Download storage above)
      const freeBytes = downloadsFreeBytes(this.downloadStorageStatus)
      if (freeBytes === null) return this.$getString('LabelDownloadsStorageUsed', [used])
      return this.$getString('LabelDownloadsStorage', [used, this.$bytesPretty(freeBytes, 1)])
    },
    autoDownloadKeepCountOption() {
      return String(this.settings.autoDownloadKeepCount)
    },
    autoDownloadIntervalOption() {
      return this.autoDownloadIntervalItems.find((i) => i.value === this.settings.autoDownloadIntervalHours)?.text || 'Error'
    },
    jumpForwardSecondsOptions() {
      return this.$store.state.globals.jumpForwardSecondsOptions || []
    },
    jumpBackwardsSecondsOptions() {
      return this.$store.state.globals.jumpBackwardsSecondsOptions || []
    },
    languageOptionItems() {
      return this.$languageCodeOptions || []
    },
    jumpForwardOption() {
      return this.getJumpLabel(this.settings.jumpForwardTime)
    },
    jumpBackwardsOption() {
      return this.getJumpLabel(this.settings.jumpBackwardsTime)
    },
    longJumpForwardOption() {
      return this.getLongJumpLabel(this.settings.longJumpForwardTime)
    },
    longJumpBackwardsOption() {
      return this.getLongJumpLabel(this.settings.longJumpBackwardsTime)
    },
    longJumpOptionItems() {
      return LONG_JUMP_SECONDS_OPTIONS.map((value) => ({ text: this.getLongJumpLabel(value), value }))
    },
    themeOptionItems() {
      return this.$theme.themes.map((theme) => ({ text: this.$strings[theme.labelKey], value: theme.id }))
    },
    shakeSensitivityOption() {
      const item = this.shakeSensitivityItems.find((i) => i.value === this.settings.shakeSensitivity)
      return item?.text || 'Error'
    },
    hapticFeedbackOption() {
      const item = this.hapticFeedbackItems.find((i) => i.value === this.settings.hapticFeedback)
      return item?.text || 'Error'
    },
    languageOption() {
      return this.languageOptionItems.find((i) => i.value === this.settings.languageCode)?.text || ''
    },
    themeOption() {
      // A RICE appearance (Settings → Appearance) is named, not shown as its built-in base
      const rice = this.$theme.rice && this.$theme.rice.state
      if (rice && rice.kind !== 'builtin') return rice.label || this.$strings.LabelThemeCustom
      return this.themeOptionItems.find((i) => i.value === this.theme)?.text || ''
    },
    sleepTimerLengthOption() {
      if (!this.settings.sleepTimerLength) return this.$strings.LabelEndOfChapter
      const minutes = Number(this.settings.sleepTimerLength) / 1000 / 60
      return `${minutes} min`
    },
    autoSleepTimerRewindLengthOption() {
      const minutes = Number(this.settings.autoSleepTimerAutoRewindTime) / 1000 / 60
      return `${minutes} min`
    },
    downloadUsingCellularOption() {
      const item = this.downloadUsingCellularItems.find((i) => i.value === this.settings.downloadUsingCellular)
      return item?.text || 'Error'
    },
    streamingUsingCellularOption() {
      const item = this.streamingUsingCellularItems.find((i) => i.value === this.settings.streamingUsingCellular)
      return item?.text || 'Error'
    },
    androidAutoBrowseSeriesSequenceOrderOption() {
      const item = this.androidAutoBrowseSeriesSequenceOrderItems.find((i) => i.value === this.settings.androidAutoBrowseSeriesSequenceOrder)
      return item?.text || 'Error'
    },
    downloadStorageView() {
      return storageStatusView(this.downloadStorageStatus, this.$strings, this.$bytesPretty)
    },
    downloadStorageValue() {
      return String(this.downloadStorageStatus?.downloadStorage || '').startsWith('sd:') ? 'sd' : 'internal'
    },
    downloadStorageItems() {
      return [
        { text: this.$strings.LabelDownloadStorageInternal, value: 'internal' },
        { text: this.downloadStorageView.sdSelectable ? this.$strings.LabelDownloadStorageSdCard : this.$strings.LabelDownloadStorageUnavailable, value: 'sd' }
      ]
    },
    downloadStorageOption() {
      return this.downloadStorageValue === 'sd' ? this.$strings.LabelDownloadStorageSdCard : this.$strings.LabelDownloadStorageInternal
    },
    moreMenuItems() {
      if (this.moreMenuSetting === 'shakeSensitivity') return this.shakeSensitivityItems
      else if (this.moreMenuSetting === 'hapticFeedback') return this.hapticFeedbackItems
      else if (this.moreMenuSetting === 'language') return this.languageOptionItems
      else if (this.moreMenuSetting === 'theme') return this.themeOptionItems
      else if (this.moreMenuSetting === 'downloadUsingCellular') return this.downloadUsingCellularItems
      else if (this.moreMenuSetting === 'streamingUsingCellular') return this.streamingUsingCellularItems
      else if (this.moreMenuSetting === 'downloadStorage') return this.downloadStorageItems
      else if (this.moreMenuSetting === 'androidAutoBrowseSeriesSequenceOrder') return this.androidAutoBrowseSeriesSequenceOrderItems
      else if (this.moreMenuSetting === 'diagnosticLevel') return this.diagnosticLevelItems
      else if (this.moreMenuSetting === 'autoDownloadKeepCount') return this.autoDownloadKeepCountItems
      else if (this.moreMenuSetting === 'autoDownloadIntervalHours') return this.autoDownloadIntervalItems
      else if (this.moreMenuSetting === 'autoDeleteDelayDays') return this.autoDeleteDelayItems
      else if (this.moreMenuSetting === 'downloadsLimitGb') return this.downloadsLimitItems
      else if (this.moreMenuSetting === 'jumpForward')
        return this.jumpForwardSecondsOptions.map((value) => ({
          text: this.getJumpLabel(value),
          value: value
        }))
      else if (this.moreMenuSetting === 'jumpBackwards')
        return this.jumpBackwardsSecondsOptions.map((value) => ({
          text: this.getJumpLabel(value),
          value: value
        }))
      else if (this.moreMenuSetting === 'longJumpForward' || this.moreMenuSetting === 'longJumpBackwards') return this.longJumpOptionItems
      return []
    },
    moreMenuSelected() {
      if (this.moreMenuSetting === 'jumpForward') return this.settings.jumpForwardTime
      if (this.moreMenuSetting === 'jumpBackwards') return this.settings.jumpBackwardsTime
      if (this.moreMenuSetting === 'longJumpForward') return this.settings.longJumpForwardTime
      if (this.moreMenuSetting === 'longJumpBackwards') return this.settings.longJumpBackwardsTime
      if (this.moreMenuSetting === 'language') return this.settings.languageCode
      if (this.moreMenuSetting === 'theme') return this.theme
      if (this.moreMenuSetting === 'downloadUsingCellular') return this.settings.downloadUsingCellular
      if (this.moreMenuSetting === 'streamingUsingCellular') return this.settings.streamingUsingCellular
      if (this.moreMenuSetting === 'downloadStorage') return this.downloadStorageValue
      if (this.moreMenuSetting === 'androidAutoBrowseSeriesSequenceOrder') return this.settings.androidAutoBrowseSeriesSequenceOrder
      if (this.moreMenuSetting === 'diagnosticLevel') return this.diagnosticLevel
      if (this.moreMenuSetting === 'shakeSensitivity') return this.settings.shakeSensitivity
      if (this.moreMenuSetting === 'hapticFeedback') return this.settings.hapticFeedback
      if (this.moreMenuSetting === 'autoDownloadKeepCount') return this.settings.autoDownloadKeepCount
      if (this.moreMenuSetting === 'autoDownloadIntervalHours') return this.settings.autoDownloadIntervalHours
      if (this.moreMenuSetting === 'autoDeleteDelayDays') return this.settings.autoDeleteDelayDays
      if (this.moreMenuSetting === 'downloadsLimitGb') return this.settings.downloadsLimitGb
      return null
    }
  },
  methods: {
    sleepTimerLengthModalSelection(value) {
      this.settings.sleepTimerLength = value
      this.saveSettings()
    },
    showAutoSleepTimerRewindLengthModalSelection(value) {
      this.settings.autoSleepTimerAutoRewindTime = value
      this.saveSettings()
    },
    showSleepTimerOptions() {
      this.showSleepTimerLengthModal = true
    },
    showAutoSleepTimerRewindOptions() {
      this.showAutoSleepTimerRewindLengthModal = true
    },
    showHapticFeedbackOptions() {
      this.moreMenuSetting = 'hapticFeedback'
      this.showMoreMenuDialog = true
    },
    showShakeSensitivityOptions() {
      this.moreMenuSetting = 'shakeSensitivity'
      this.showMoreMenuDialog = true
    },
    showLanguageOptions() {
      this.moreMenuSetting = 'language'
      this.showMoreMenuDialog = true
    },
    showThemeOptions() {
      this.moreMenuSetting = 'theme'
      this.showMoreMenuDialog = true
    },
    showJumpForwardOptions() {
      this.moreMenuSetting = 'jumpForward'
      this.showMoreMenuDialog = true
    },
    showJumpBackwardsOptions() {
      this.moreMenuSetting = 'jumpBackwards'
      this.showMoreMenuDialog = true
    },
    showLongJumpForwardOptions() {
      this.moreMenuSetting = 'longJumpForward'
      this.showMoreMenuDialog = true
    },
    showLongJumpBackwardsOptions() {
      this.moreMenuSetting = 'longJumpBackwards'
      this.showMoreMenuDialog = true
    },
    getLongJumpLabel(seconds) {
      return normalizeLongJumpTime(seconds) ? this.getJumpLabel(seconds) : this.$strings.LabelOff
    },
    showDownloadUsingCellularOptions() {
      this.moreMenuSetting = 'downloadUsingCellular'
      this.showMoreMenuDialog = true
    },
    showStreamingUsingCellularOptions() {
      this.moreMenuSetting = 'streamingUsingCellular'
      this.showMoreMenuDialog = true
    },
    showDownloadStorageOptions() {
      this.moreMenuSetting = 'downloadStorage'
      this.showMoreMenuDialog = true
    },
    showAndroidAutoBrowseSeriesSequenceOrderOptions() {
      this.moreMenuSetting = 'androidAutoBrowseSeriesSequenceOrder'
      this.showMoreMenuDialog = true
    },
    showDiagnosticLevelOptions() {
      this.moreMenuSetting = 'diagnosticLevel'
      this.showMoreMenuDialog = true
    },
    showAutoDownloadKeepCountOptions() {
      this.moreMenuSetting = 'autoDownloadKeepCount'
      this.showMoreMenuDialog = true
    },
    showAutoDownloadIntervalOptions() {
      this.moreMenuSetting = 'autoDownloadIntervalHours'
      this.showMoreMenuDialog = true
    },
    showAutoDeleteDelayOptions() {
      this.moreMenuSetting = 'autoDeleteDelayDays'
      this.showMoreMenuDialog = true
    },
    showDownloadsLimitOptions() {
      this.moreMenuSetting = 'downloadsLimitGb'
      this.showMoreMenuDialog = true
    },
    async loadDownloadsStorage() {
      if (!this.isAndroid) return
      try {
        this.downloadsStorage = await AbsDownloader.getDownloadsStorage()
      } catch (error) {
        console.error('Failed to load downloads storage', error)
      }
    },
    clickMenuAction(action) {
      this.showMoreMenuDialog = false
      if (this.moreMenuSetting === 'shakeSensitivity') {
        this.settings.shakeSensitivity = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'hapticFeedback') {
        this.settings.hapticFeedback = action
        this.hapticFeedbackUpdated(action)
      } else if (this.moreMenuSetting === 'language') {
        this.settings.languageCode = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'theme') {
        this.theme = action
        this.saveTheme(action)
      } else if (this.moreMenuSetting === 'downloadUsingCellular') {
        this.settings.downloadUsingCellular = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'streamingUsingCellular') {
        this.settings.streamingUsingCellular = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'downloadStorage') {
        this.selectDownloadStorage(action)
      } else if (this.moreMenuSetting === 'androidAutoBrowseSeriesSequenceOrder') {
        this.settings.androidAutoBrowseSeriesSequenceOrder = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'diagnosticLevel') {
        this.setDiagnosticLevel(action)
      } else if (this.moreMenuSetting === 'autoDownloadKeepCount') {
        this.settings.autoDownloadKeepCount = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'autoDownloadIntervalHours') {
        this.settings.autoDownloadIntervalHours = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'autoDeleteDelayDays') {
        this.settings.autoDeleteDelayDays = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'downloadsLimitGb') {
        this.settings.downloadsLimitGb = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'jumpForward') {
        this.settings.jumpForwardTime = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'jumpBackwards') {
        this.settings.jumpBackwardsTime = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'longJumpForward') {
        this.settings.longJumpForwardTime = action
        this.saveSettings()
      } else if (this.moreMenuSetting === 'longJumpBackwards') {
        this.settings.longJumpBackwardsTime = action
        this.saveSettings()
      }
    },
    async selectDownloadStorage(target) {
      if (this.settingDownloadStorage) return
      const status = this.downloadStorageStatus
      if (target === 'internal' && this.downloadStorageValue === 'internal') return
      // Another card in the slot is a new target even though the setting already says SD
      if (target === 'sd' && status?.sd?.uuid && status.downloadStorage === `sd:${status.sd.uuid}`) return
      if (target === 'sd' && !this.downloadStorageView.sdSelectable) {
        this.$toast.error(this.$strings.LabelDownloadStorageUnavailable)
        return
      }
      this.settingDownloadStorage = true
      try {
        const res = await this.$db.setDownloadStorage(target)
        if (res?.errorCode === 'noSpace') {
          this.$toast.error(this.$getString('MessageDownloadStorageNoSpace', [this.$bytesPretty(res.requiredBytes), this.$bytesPretty(res.freeBytes)]))
        } else if (res?.errorCode === 'noSdCard') {
          this.$toast.error(this.$strings.LabelDownloadStorageUnavailable)
        } else if (res?.error) {
          this.$toast.error(res.error)
        } else {
          await this.refreshDownloadStorageStatus()
        }
      } catch (error) {
        console.error('[settings] Failed to set download storage', error)
        this.$toast.error(error?.message || String(error))
      } finally {
        this.settingDownloadStorage = false
      }
    },
    async refreshDownloadStorageStatus() {
      try {
        this.downloadStorageStatus = await this.$db.getDownloadStorageStatus()
      } catch (error) {
        console.error('[settings] Failed to read download storage status', error)
      }
    },
    async initDownloadStorage() {
      const listener = await AbsDatabase.addListener('onDownloadStorageStatus', (status) => {
        this.downloadStorageStatus = status
      })
      // Left the page before the listener was registered
      if (this._isDestroyed) {
        listener?.remove()
        return
      }
      this.downloadStorageListener = listener
      this.refreshDownloadStorageStatus()
    },
    async saveTheme(theme) {
      this.theme = await this.$theme.select(theme)
    },
    autoSleepTimerTimeUpdated(val) {
      if (!val) return // invalid times return falsy
      this.saveSettings()
    },
    androidAutoBrowseLimitForGroupingUpdated(val) {
      if (!val) return // invalid times return falsy
      if (val > 1000) val = 1000
      if (val < 30) val = 30
      this.saveSettings()
    },
    hapticFeedbackUpdated(val) {
      this.$store.commit('globals/setHapticFeedback', val)
      this.saveSettings()
    },
    showInfo(setting) {
      if (this.settingInfo[setting]) {
        Dialog.alert({
          title: this.settingInfo[setting].name,
          message: this.settingInfo[setting].message
        })
      }
    },
    async showConfirmMp3IndexSeeking() {
      const confirmResult = await Dialog.confirm({
        title: this.settingInfo.enableMp3IndexSeeking.name,
        message: this.settingInfo.enableMp3IndexSeeking.message,
        cancelButtonTitle: 'View More'
      })
      if (!confirmResult.value) {
        window.open('https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files', '_blank')
      }
    },
    toggleEnableMp3IndexSeeking() {
      this.settings.enableMp3IndexSeeking = !this.settings.enableMp3IndexSeeking
      this.saveSettings()
    },
    toggleAutoSleepTimer() {
      this.settings.autoSleepTimer = !this.settings.autoSleepTimer
      this.saveSettings()
    },
    toggleAutoSleepTimerAutoRewind() {
      this.settings.autoSleepTimerAutoRewind = !this.settings.autoSleepTimerAutoRewind
      this.saveSettings()
    },
    toggleDisableSleepTimerFadeOut() {
      this.settings.disableSleepTimerFadeOut = !this.settings.disableSleepTimerFadeOut
      this.saveSettings()
    },
    toggleDisableShakeToResetSleepTimer() {
      this.settings.disableShakeToResetSleepTimer = !this.settings.disableShakeToResetSleepTimer
      this.saveSettings()
    },
    toggleDisableSleepTimerResetFeedback() {
      this.settings.disableSleepTimerResetFeedback = !this.settings.disableSleepTimerResetFeedback
      this.saveSettings()
    },
    toggleSleepTimerAlmostDoneChime() {
      this.settings.enableSleepTimerAlmostDoneChime = !this.settings.enableSleepTimerAlmostDoneChime
      this.saveSettings()
    },
    toggleDisableAutoRewind() {
      this.settings.disableAutoRewind = !this.settings.disableAutoRewind
      this.saveSettings()
    },
    toggleEnableAltView() {
      this.settings.enableAltView = !this.settings.enableAltView
      this.saveSettings()
    },
    toggleAllowSeekingOnMediaControls() {
      this.settings.allowSeekingOnMediaControls = !this.settings.allowSeekingOnMediaControls
      this.saveSettings()
    },
    toggleAskBeforeStreamingIncomplete() {
      this.settings.askBeforeStreamingIncomplete = !this.settings.askBeforeStreamingIncomplete
      this.saveSettings()
    },
    getCurrentOrientation() {
      const orientation = window.screen?.orientation || {}
      const type = orientation.type || ''

      if (type.includes('landscape')) return 'LANDSCAPE'
      return 'PORTRAIT' // default
    },
    toggleLockOrientation() {
      this.lockCurrentOrientation = !this.lockCurrentOrientation
      if (this.lockCurrentOrientation) {
        this.settings.lockOrientation = this.getCurrentOrientation()
      } else {
        this.settings.lockOrientation = 'NONE'
      }
      this.$setOrientationLock(this.settings.lockOrientation)
      this.saveSettings()
    },
    async saveSettings() {
      await this.$hapticsImpact()
      const updatedDeviceData = await this.$db.updateDeviceSettings({ ...this.settings })
      if (updatedDeviceData) {
        this.$store.commit('setDeviceData', updatedDeviceData)
        this.deviceData = updatedDeviceData
        this.$setLanguageCode(updatedDeviceData.deviceSettings?.languageCode || 'en-us')
        this.setDeviceSettings()
        this.loadDownloadsStorage()
      }
    },
    setDeviceSettings() {
      const deviceSettings = this.deviceData.deviceSettings || {}
      this.settings.disableAutoRewind = !!deviceSettings.disableAutoRewind
      this.settings.enableAltView = !!deviceSettings.enableAltView
      this.settings.allowSeekingOnMediaControls = !!deviceSettings.allowSeekingOnMediaControls
      this.settings.askBeforeStreamingIncomplete = !!deviceSettings.askBeforeStreamingIncomplete
      this.settings.jumpForwardTime = deviceSettings.jumpForwardTime || 10
      this.settings.externalMediaNavigation = ['chapters', 'files'].includes(deviceSettings.externalMediaNavigation) ? deviceSettings.externalMediaNavigation : 'time'
      this.settings.jumpBackwardsTime = deviceSettings.jumpBackwardsTime || 10
      this.settings.longJumpForwardTime = normalizeLongJumpTime(deviceSettings.longJumpForwardTime)
      this.settings.longJumpBackwardsTime = normalizeLongJumpTime(deviceSettings.longJumpBackwardsTime)
      this.settings.enableMp3IndexSeeking = !!deviceSettings.enableMp3IndexSeeking

      this.settings.lockOrientation = deviceSettings.lockOrientation || 'NONE'
      this.lockCurrentOrientation = this.settings.lockOrientation !== 'NONE'
      this.settings.hapticFeedback = deviceSettings.hapticFeedback || 'LIGHT'

      this.settings.disableShakeToResetSleepTimer = !!deviceSettings.disableShakeToResetSleepTimer
      this.settings.shakeSensitivity = deviceSettings.shakeSensitivity || 'MEDIUM'
      this.settings.autoSleepTimer = !!deviceSettings.autoSleepTimer
      this.settings.autoSleepTimerStartTime = deviceSettings.autoSleepTimerStartTime || '22:00'
      this.settings.autoSleepTimerEndTime = deviceSettings.autoSleepTimerEndTime || '06:00'
      this.settings.sleepTimerLength = !isNaN(deviceSettings.sleepTimerLength) ? deviceSettings.sleepTimerLength : 900000 // 15 minutes
      this.settings.disableSleepTimerFadeOut = !!deviceSettings.disableSleepTimerFadeOut
      this.settings.disableSleepTimerResetFeedback = !!deviceSettings.disableSleepTimerResetFeedback
      this.settings.enableSleepTimerAlmostDoneChime = !!deviceSettings.enableSleepTimerAlmostDoneChime

      this.settings.autoSleepTimerAutoRewind = !!deviceSettings.autoSleepTimerAutoRewind
      this.settings.autoSleepTimerAutoRewindTime = !isNaN(deviceSettings.autoSleepTimerAutoRewindTime) ? deviceSettings.autoSleepTimerAutoRewindTime : 300000 // 5 minutes

      this.settings.languageCode = deviceSettings.languageCode || 'en-us'

      this.settings.downloadUsingCellular = deviceSettings.downloadUsingCellular || 'ALWAYS'
      this.settings.streamingUsingCellular = deviceSettings.streamingUsingCellular || 'ALWAYS'

      this.settings.androidAutoBrowseLimitForGrouping = deviceSettings.androidAutoBrowseLimitForGrouping
      this.settings.androidAutoBrowseSeriesSequenceOrder = deviceSettings.androidAutoBrowseSeriesSequenceOrder || 'ASC'

      this.settings.autoDownloadEnabled = !!deviceSettings.autoDownloadEnabled
      this.settings.autoDownloadAllPodcasts = !!deviceSettings.autoDownloadAllPodcasts
      this.settings.autoDownloadKeepCount = Number.isInteger(deviceSettings.autoDownloadKeepCount) && deviceSettings.autoDownloadKeepCount > 0 ? deviceSettings.autoDownloadKeepCount : 3
      this.settings.autoDownloadWifiOnly = deviceSettings.autoDownloadWifiOnly !== false
      this.settings.autoDownloadChargingOnly = !!deviceSettings.autoDownloadChargingOnly
      this.settings.autoDownloadIntervalHours = [1, 6, 12, 24].includes(deviceSettings.autoDownloadIntervalHours) ? deviceSettings.autoDownloadIntervalHours : 6

      this.settings.autoDeleteFinishedBooks = !!deviceSettings.autoDeleteFinishedBooks
      this.settings.autoDeleteDelayDays = normalizeDelayDays(deviceSettings.autoDeleteDelayDays)
      this.settings.downloadsLimitGb = normalizeLimitGb(deviceSettings.downloadsLimitGb)
    },
    async init() {
      this.loading = true
      await this.$theme.ready
      this.theme = this.$theme.id
      this.deviceData = await this.$db.getDeviceData()
      this.$store.commit('setDeviceData', this.deviceData)
      this.setDeviceSettings()
      this.loading = false
      this.loadDownloadsStorage()
    }
  },
  mounted() {
    this.init()
    if (!this.isiOS) this.loadDiagnosticState()
    if (this.isAndroid) this.initDownloadStorage()
  },
  beforeDestroy() {
    this.downloadStorageListener?.remove()
  }
}
</script>
