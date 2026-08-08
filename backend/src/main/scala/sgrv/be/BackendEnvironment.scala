package sgrv.be

import sgrv.be.auth.{GoogleOAuth, SessionStore, TokenGenerator}
import sgrv.be.sheets.SheetsClient

/** Services available to dynamically discovered backend routes. */
type BackendEnvironment = GoogleOAuth & SessionStore & TokenGenerator & SheetsClient
