package cz.loplex.dogvision.web

import cz.loplex.dogvision.texts.Texts

fun main() {
    Page(Texts.of(Language.preferred())).start()
}
