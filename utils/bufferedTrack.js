// A downloaded file has nothing to buffer: ExoPlayer still reports a read-ahead, which would draw a streaming bar
export function drawnBufferedTime(isLocal, bufferedTime) {
  return isLocal ? 0 : bufferedTime
}
