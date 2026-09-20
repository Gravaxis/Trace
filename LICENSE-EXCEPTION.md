# Additional permission under GNU GPL version 3 section 7

> **Editorial note — read before relying on this file.**
> This text is a draft prepared by adapting the FSF "linking over a controlled interface"
> template (published by SPDX as `GPL-3.0-interface-exception`). It has **not** been reviewed by a
> lawyer. It is recorded here from the first commit so that every contribution is made under
> "GPL-3.0-or-later WITH this exception", which is far easier than collecting agreement later —
> GPLv3 §7 additional permissions must come from the copyright holders. Treat the wording as
> provisional until counsel has reviewed it, and do not describe it publicly as legal advice.

## Scope

This additional permission applies to every part of Trace that is licensed under the GNU General
Public License version 3 or later (see `COPYING`). It does **not** apply to `trace-api`, which is
licensed under the Apache License 2.0 (see `trace-api/LICENSE`) and needs no exception.

"The Trace API" below means the public types published in the Maven artifact
`in.gravaxis:trace-api`, in the Java package `in.gravaxis.trace.api` and its subpackages, together
with the Bukkit `ServicesManager` registration through which that API is obtained.

## Exception text

Linking Trace statically or dynamically with other modules is making a combined work based on
Trace. Thus, the terms and conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of Trace give you permission to combine Trace with
free software programs or libraries that are released under the GNU LGPL and with independent
modules that communicate with Trace solely through the Trace API. You may copy and distribute such
a system following the terms of the GNU GPL for Trace and the licenses of the other code
concerned, provided that you include the source code of that other code when and as the GNU GPL
requires distribution of source code and provided that you do not modify the Trace API.

Note that people who make modified versions of Trace are not obligated to grant this special
exception for their modified versions; it is their choice whether to do so. The GNU General Public
License gives permission to release a modified version without this exception; this exception also
makes it possible to release a modified version which carries forward this exception. If you
modify the Trace API, this exception does not apply to your modified version of Trace, and you
must remove this exception when you distribute your modified version.

This exception is an additional permission under section 7 of the GNU General Public License,
version 3 ("GPLv3").

## What this means in practice

* A plugin — closed source or otherwise — that only calls `in.gravaxis:trace-api` is not made a
  derivative work of the GPL implementation by doing so.
* Forking the implementation, bundling it, or shipping a modified Trace remains subject to the GPL,
  including its source-offer obligations.
* GPLv3 §7 allows a downstream conveyor to remove additional permissions from their copy. The
  exception protects users of the official distribution; it is not a promise about every fork.
