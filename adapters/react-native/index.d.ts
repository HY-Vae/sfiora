export type {
  AndroidScanPresentation,
  NdefRecordSnapshot,
  NdefSnapshot,
  NdefStatus,
  NfcAndroidScanOptions,
  NfcByteValue,
  NfcCapabilities,
  NfcFormatOptions,
  NfcFormatMessages,
  NfcFormatResult,
  NfcFormattedResult,
  NfcFormatPreservedResult,
  NfcForegroundDispatchState,
  NfcPresentationState,
  NfcWaitForPresentationEndOptions,
  NfcErrorCode,
  NfcErrorPayload,
  NfcIosScanOptions,
  NfcJsonObject,
  NfcJsonPrimitive,
  NfcJsonValue,
  NfcNativeError,
  NfcNdefExternalRecordInput,
  NfcNdefExternalTypeMarker,
  NfcNdefInitializationMarkerResult,
  NfcNdefInitializationResult,
  NfcNdefInitializedResult,
  NfcNdefPreservedResult,
  NfcNdefMessageInput,
  NfcNdefMimeRecordInput,
  NfcNdefRecordInput,
  NfcNdefTextEncoding,
  NfcNdefTextRecordInput,
  NfcNdefUriRecordInput,
  NfcPlatform,
  NfcPlatformDetails,
  NfcPollingTechnology,
  NfcReadMode,
  NfcScanOptions,
  NfcScanMessages,
  NfcTagSnapshot,
  NfcTechnology,
  NfcWriteOptions,
  NfcWriteMessages,
  NfcWriteResult,
} from './contract/types';

import type {
  NfcCapabilities,
  NfcFormatOptions,
  NfcFormatResult,
  NfcForegroundDispatchState,
  NfcPresentationState,
  NfcWaitForPresentationEndOptions,
  NfcErrorCode,
  NfcNativeError,
  NfcNdefExternalTypeMarker,
  NfcNdefInitializationResult,
  NfcNdefMessageInput,
  NfcScanOptions,
  NfcTagSnapshot,
  NfcWriteOptions,
  NfcWriteResult,
} from './contract/types';

export class SfioraError extends Error {
  readonly code: NfcErrorCode;
  readonly recoverable: boolean;
  readonly nativeError?: NfcNativeError;
  readonly cause?: unknown;
}

export function getCapabilities(): Promise<NfcCapabilities>;
export function startScan(options?: NfcScanOptions): Promise<NfcTagSnapshot>;
export function cancelScan(): Promise<void>;
export function isScanning(): Promise<boolean>;
export function writeNdef(
  message: NfcNdefMessageInput,
  options?: NfcWriteOptions
): Promise<NfcWriteResult>;
export function initializeNdef(
  message: NfcNdefMessageInput,
  marker: NfcNdefExternalTypeMarker,
  options?: NfcWriteOptions
): Promise<NfcNdefInitializationResult>;
/** Explicit Android NDEF formatting with a managed panel and live verification.
 * Existing NDEF tags are preserved without writes; inspect action and verified.
 * Cancellation does not undo an issued format command. iOS rejects NFC_UNSUPPORTED.
 */
export function formatNdef(options?: NfcFormatOptions): Promise<NfcFormatResult>;
/** Cancels writing, initialization or formatting; does not undo tag changes. */
export function cancelWrite(): Promise<void>;
/** Includes writing, initialization and formatting through verification. */
export function isWriting(): Promise<boolean>;

export interface NfcWaitForIdleOptions {
  /** Integer 1–60000; default 5000 milliseconds. */
  timeoutMilliseconds?: number;
}
/** Wait for this bridge's session to close. Does not cancel it or reserve the next session.
 * Rejects with SESSION_CLOSE_TIMEOUT on deadline; native busy state is unchanged.
 */
export function waitForIdle(options?: NfcWaitForIdleOptions): Promise<void>;

/** Opt-in Android foreground dispatch. Owner acquisition/release is idempotent and independent of RF sessions.
 * Requests survive pause/resume and end when released or the native bridge is destroyed.
 * Inspect the returned state; a fulfilled call does not mean NFC is enabled. iOS rejects NFC_UNSUPPORTED.
 */
export function acquireForegroundDispatch(ownerId: string): Promise<NfcForegroundDispatchState>;
export function releaseForegroundDispatch(ownerId: string): Promise<NfcForegroundDispatchState>;
export function getForegroundDispatchState(): Promise<NfcForegroundDispatchState>;
export function getPresentationState(): Promise<NfcPresentationState>;
/** Captures currently visible Sfiora Android panels. Later panels do not extend this wait.
 * Call after an operation result. Does not cancel, reserve a session, or imply RF idle.
 * Rejects PRESENTATION_TIMEOUT on deadline; iOS rejects NFC_UNSUPPORTED.
 */
export function waitForPresentationEnd(options?: NfcWaitForPresentationEndOptions): Promise<void>;
