# Media upload companion

chat_upload.php is the existing server helper used by the iOS media-resume integration tests. It contains no production database configuration or signing secrets.

It is not a standalone API server. Production integration still requires the authenticated upload.message.status and upload.message.chunk routes in the existing SPX backend. This repository does not automatically deploy or change the production server.

The local test router under ios-native/scripts selects this copy when the original sibling spxchat/chat_upload.php is absent.
