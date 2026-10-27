# Copyright (c) 2024 The Brave Authors. All rights reserved.
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at https://mozilla.org/MPL/2.0/.

import brave_chromium_utils
import override_utils


@override_utils.override_function(globals())
def _GenerateConfigXmlTree(
    original_function, orig_config_path, backported_methods
):
    # Expand upstream's lint-suppressions.xml with python chromium_src override
    original_root_node = original_function(orig_config_path, backported_methods)

    if orig_config_path:
        brave_config_path = brave_chromium_utils.get_chromium_src_override(
            orig_config_path
        )
        brave_root_node = ElementTree.parse(brave_config_path).getroot()
        for item in brave_root_node.findall('issue'):
            original_root_node.append(item)

    return original_root_node


def _PrepareSelectedResourceView(resource_sources, lint_gen_dir, baseline):
    # The resource selectors intentionally isolate qualifiers so packaging cannot
    # rediscover excluded files. Lint needs sibling qualifiers in one resource
    # root. Build that view from the explicit input list, never a directory scan.
    view_root = os.path.join(lint_gen_dir, 'SELECTED_RESOURCES')
    baseline_copy = os.path.join(lint_gen_dir, 'selected-resources-baseline.xml')
    copies = {}
    metadata = []
    result = []
    for source in sorted(set(resource_sources or [])):
        absolute = os.path.abspath(source)
        if os.path.commonpath((absolute, os.path.abspath(view_root))) == os.path.abspath(view_root):
            raise ValueError('Selected resource view cannot be its own input: ' + source)
        parts = os.path.relpath(absolute).split(os.sep)
        if (len(parts) < 5 or parts[0] != 'gen'
                or not parts[-4].endswith('_selected')):
            result.append(source)
            continue
        encoded_directory, qualifier, filename = parts[-3:]
        suffix = '_' + qualifier
        if not encoded_directory.endswith(suffix):
            result.append(source)
            continue
        encoded_parent = encoded_directory[:-len(suffix)]
        if not encoded_parent:
            result.append(source)
            continue
        destination = os.path.join(view_root, *parts[:-3], encoded_parent,
                                   qualifier, filename)
        previous = copies.get(destination)
        if previous is not None and previous != absolute:
            raise ValueError('Conflicting selected resource inputs: ' + destination)
        copies[destination] = absolute
        target_directory = os.path.join(build_utils.DIR_SOURCE_ROOT, *parts[1:-4])
        metadata.append((absolute, destination, target_directory,
                         encoded_directory, qualifier, filename))
        result.append(destination)

    # Only this lint-owned view is purged. Packaging roots and exclusions stay
    # intact; old unlisted files cannot survive into another lint invocation.
    if os.path.lexists(view_root):
        shutil.rmtree(view_root)
    if os.path.lexists(baseline_copy):
        os.unlink(baseline_copy)
    for destination, source in copies.items():
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copyfile(source, destination)

    if not metadata or not baseline or not os.path.isfile(baseline):
        return result, baseline
    tree = ElementTree.parse(baseline)
    changed = False
    for location in tree.iter('location'):
        old_path = location.get('file')
        if not old_path:
            continue
        original = os.path.abspath(old_path)
        if not os.path.isfile(original):
            continue
        directory, filename = os.path.split(original)
        with open(original, 'rb') as f:
            original_bytes = f.read()
        matches = set()
        for source, destination, target, encoded, qualifier, name in metadata:
            if filename != name:
                continue
            # These are the two literal directory encodings used by copy_ex's
            # selected-resource GN callers: target-relative and //source-root.
            encodings = (
                os.path.relpath(directory, target).replace(os.sep, '_'),
                ('//' + os.path.relpath(directory, build_utils.DIR_SOURCE_ROOT)).replace(os.sep, '_'),
            )
            if original != source and (os.path.basename(directory) != qualifier
                                       or encoded not in encodings):
                continue
            with open(source, 'rb') as f:
                if f.read() == original_bytes:
                    matches.add(destination)
        # Do not create baseline entries or guess between two resource owners.
        # A changed or ambiguous source keeps its original, unmatched location.
        if len(matches) == 1:
            location.set('file', matches.pop())
            changed = True
    if changed:
        _WriteXmlFile(tree.getroot(), baseline_copy)
        baseline = baseline_copy
    return result, baseline


@override_utils.override_function(globals())
def _GenerateProjectFile(
    original_function,
    android_manifest,
    android_sdk_root,
    cache_dir,
    partials_dir,
    sources=None,
    classpath=None,
    srcjar_sources=None,
    resource_sources=None,
    aars=None,
    android_sdk_version=None,
    baseline_path=None,
    android_manifests=None,
):
    resource_sources, baseline_path = _PrepareSelectedResourceView(
        resource_sources, os.path.dirname(partials_dir), baseline_path)
    project = original_function(
        android_manifest, android_sdk_root, cache_dir, partials_dir, sources,
        classpath, srcjar_sources, resource_sources, aars, android_sdk_version,
        baseline_path, android_manifests)
    if android_manifest and android_manifests is None:
        # Preserve the legacy direct-call fallback when no source list is given.
        # Normal GN callers pass explicit unmerged roots to the original function.
        manifest = ElementTree.SubElement(project.find('module'), 'manifest')
        manifest.set('file', android_manifest)
    return project
