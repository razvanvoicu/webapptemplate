package sgrv.be.sheets

class SheetsRoutesSuite extends munit.FunSuite:

  test("extracts the name field from a JSON request body"):
    assertEquals(SheetsRoutes.extractName("""{"name":"My Sheet"}"""), Some("My Sheet"))
    assertEquals(
      SheetsRoutes.extractName("""{"name":"Quote \" and backslash \\"}"""),
      Some("Quote \" and backslash \\")
    )
    assertEquals(SheetsRoutes.extractName("""{"name":"  "}"""), None)
    assertEquals(SheetsRoutes.extractName("{}"), None)
    assertEquals(SheetsRoutes.extractName("not json"), None)

  test("renders spreadsheet rows as escaped JSON"):
    assertEquals(SheetsRoutes.rowsJson(Seq.empty), """{"rows":[]}""")
    assertEquals(
      SheetsRoutes.rowsJson(Seq(Seq("2026-08-08T00:00:00Z", "Mozilla/5.0 \"test\""))),
      """{"rows":[["2026-08-08T00:00:00Z","Mozilla/5.0 \"test\""]]}"""
    )
    assertEquals(
      SheetsRoutes.rowsJson(Seq(Seq("a", "b"), Seq("c", "d"))),
      """{"rows":[["a","b"],["c","d"]]}"""
    )
