package sgrv.be

import sgrv.be.auth.{GoogleOAuth, SessionStore, TokenGenerator}

/** Services available to dynamically discovered backend routes. */
type BackendEnvironment = GoogleOAuth & SessionStore & TokenGenerator
