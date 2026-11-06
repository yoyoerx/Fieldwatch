package app.fieldwatch.domain

/** Copy for the coincidence report and the stops warning. English stays here. Packs carry the other languages. */
internal object PlaceLines {
    val all: List<EnglishLine> = listOf(
        en("reports.places.today", "Today", LanguageSlot.CONTROL),
        en("reports.places.week", "7 days", LanguageSlot.CONTROL),
        en("reports.places.today.blurb", "Heard at two or more of your stops today.", LanguageSlot.BODY),
        en("reports.places.summary", "{n} radios across {stops} of your stops", LanguageSlot.BODY),
        en("reports.places.days.one", "1 day", LanguageSlot.CONTROL),
        en("reports.places.days", "{n} days", LanguageSlot.CONTROL),
        en("reports.places.avg", "avg {dbm} dBm", LanguageSlot.CONTROL),
        en("reports.places.score", "score {n}", LanguageSlot.CONTROL),
        en("reports.places.kind.tag", "Tag", LanguageSlot.CONTROL),
        en("reports.places.kind.hotspot", "Wi-Fi hotspot", LanguageSlot.CONTROL),
        en("reports.places.kind.ble", "Bluetooth", LanguageSlot.CONTROL),
        en("reports.places.kind.drone", "Drone", LanguageSlot.CONTROL),
        en("reports.places.likely", "Likely yours", LanguageSlot.CONTROL),
        en("reports.places.mine", "This is mine", LanguageSlot.CONTROL),
        en("reports.places.mine.all", "Mark {n} likely mine", LanguageSlot.CONTROL),
        en(
            "reports.places.mine.note",
            "At most of your stops, at a strong signal. Tags are never marked for you.",
            LanguageSlot.BODY,
        ),
        en(
            "reports.places.mine.ask",
            "Mark these {n} radios as Mine? They leave this list and stop warning. Undo it on each radio's page.",
            LanguageSlot.BODY,
        ),
        en("place.wide", "Track every fixed-address radio", LanguageSlot.CONTROL),
        en("place.wide.note", "Also keeps fixed-address Bluetooth and Wi-Fi access points.", LanguageSlot.BODY),
        en("place.alert", "Warn at this many stops in a day", LanguageSlot.CONTROL),
        en("place.alert.off", "Off", LanguageSlot.CONTROL),
        en("place.alert.note", "Once a day, for a radio that is not Mine.", LanguageSlot.BODY),
        en("place.alert.title", "A radio was at {n} of your stops today", LanguageSlot.BODY),
        en(
            "place.alert.body",
            "{name} was heard at {n} of your stops today. Tap to see where. If it is yours, mark it Mine.",
            LanguageSlot.BODY,
        ),
        en("place.known", "Known, not mine", LanguageSlot.CONTROL),
        en("place.known.note", "A coworker or a neighbor. Off the places report and its warning.", LanguageSlot.BODY),
        en("reports.places.known.show", "Show known ({n})", LanguageSlot.CONTROL),
        en("reports.places.known.tag", "Known", LanguageSlot.CONTROL),
        en("reports.places.known.clear", "Not known", LanguageSlot.CONTROL),
        en("reports.places.oneoff", "{n} one-off", LanguageSlot.CONTROL),
        en(
            "reports.places.routine",
            "{n} of them are routine: places you went on two or more days.",
            LanguageSlot.BODY,
        ),
        en("reports.rotation.title", "Linked across address changes (experimental)", LanguageSlot.CONTROL),
        en(
            "reports.rotation.note",
            "Follows one Bluetooth radio across its address changes. A guess. Your own gear shows here too. Clears when the app restarts.",
            LanguageSlot.BODY,
        ),
        en("reports.rotation.empty", "Nothing linked has stayed with you for 30 minutes and 2 km.", LanguageSlot.BODY),
        en("reports.rotation.line", "{n} address changes · {min} min · {km} km with you", LanguageSlot.BODY),
        en("reports.rotation.hide", "Hide", LanguageSlot.CONTROL),
    )
}
