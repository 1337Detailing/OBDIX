# Sources étudiées / licences

OBDIX implémente ici les primitives SAE J1979/ELM327 indépendamment, sans intégrer du code tiers des projets ci-dessous.

- [Foadsf/OpenOBD](https://github.com/Foadsf/OpenOBD), GPL-3.0 : ELM327 et OBD générique, pas de codage ni reset service.
- [uholeschak/ediabaslib](https://github.com/uholeschak/ediabaslib), GPL-3.0 : interpréteur EDIABAS BMW ; intégration directe imposerait la conformité GPL-3.0 et des validations spécifiques. Aucun code embarqué.
- [fr3ts0n/AndrOBD](https://github.com/fr3ts0n/AndrOBD), GPL-3.0 : référence de logiciel générique, aucun code repris.
- [gediz/bmw-unified-db](https://github.com/gediz/bmw-unified-db) : base de provenance et de licences par couche. Non embarquée, vérifier chaque couche et ses droits avant intégration.
- [sakharniy/bmw-mac-diag](https://github.com/sakharniy/bmw-mac-diag), GPL-3.0 : référence technique d'un diagnostic BMW E9x en lecture seule sans fichiers SGBD, aucune source copiée ni bibliothèque liée. Validation de licence et attribution obligatoires si intégration future de code.\n- [Expos-AI/Klemme15](https://github.com/Expos-AI/Klemme15) : référence pour BMW E-series, pas de code repris.

Dépendance USB tierce : [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android), licence MIT (v3.6.0). Crédits et texte de licence à conserver lors de la distribution. Texte : https://github.com/mik3y/usb-serial-for-android/blob/master/LICENSE.txt

Les paramètres OBD standard ne sont pas des définitions propriétaires BMW. Aucune instruction de codage ne doit être activée sans jeu de données validé par version ECU et validation matérielle.

Les adresses et types de réponses BMW-FAST/KWP sont utilisés comme faits d'interopérabilité ; le codec Kotlin et les tests sont écrits indépendamment, sans portage ou incorporation de sources GPL. Les fichiers SGBD/PRG, les scripts de codage et les bases constructeurs ne sont pas redistribués.
