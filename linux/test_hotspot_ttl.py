import contextlib
import importlib.util
import io
import os
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "hotspot_ttl", os.path.join(HERE, "hotspot_ttl.py"))
m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m)


class DryRunTests(unittest.TestCase):
    def setUp(self):
        m.DRY_RUN = True

    def tearDown(self):
        m.DRY_RUN = False

    def _run(self, fn):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            fn()
        return buf.getvalue()

    def test_on_prints_sysctl_and_rules(self):
        with mock.patch.object(m, "load_state", return_value=None), \
                mock.patch.object(m, "sysctl_get", return_value="64"), \
                mock.patch.object(m, "rule_present", return_value=False), \
                mock.patch.object(m, "is_root", return_value=True), \
                mock.patch.object(m.shutil, "which", return_value="/usr/sbin/x"):
            out = self._run(m.turn_on)
        self.assertIn("+ sysctl -q -w net.ipv4.ip_default_ttl=65", out)
        self.assertIn("+ sysctl -q -w net.ipv6.conf.all.hop_limit=65", out)
        self.assertIn("+ iptables -t mangle -A POSTROUTING ! -o lo", out)
        self.assertIn("--ttl-set 65", out)
        self.assertIn("+ ip6tables -t mangle -A POSTROUTING ! -o lo", out)
        self.assertIn("--hl-set 65", out)

    def test_off_restores_saved_values(self):
        state = {"sysctl": {"net.ipv4.ip_default_ttl": "64",
                            "net.ipv6.conf.all.hop_limit": "64",
                            "net.ipv6.conf.default.hop_limit": None}}
        with mock.patch.object(m, "load_state", return_value=state), \
                mock.patch.object(m, "rule_present", return_value=False), \
                mock.patch.object(m, "is_root", return_value=True):
            out = self._run(m.turn_off)
        self.assertIn("+ sysctl -q -w net.ipv4.ip_default_ttl=64", out)
        self.assertIn("+ sysctl -q -w net.ipv6.conf.all.hop_limit=64", out)
        self.assertNotIn("default.hop_limit", out)

    def test_off_removes_existing_rules(self):
        present = iter([True, False, True, False])
        with mock.patch.object(m, "load_state", return_value=None), \
                mock.patch.object(m, "rule_present", side_effect=lambda c, s: next(present)), \
                mock.patch.object(m, "is_root", return_value=True):
            out = self._run(m.turn_off)
        self.assertIn("-D POSTROUTING", out)


class PermissionTests(unittest.TestCase):
    def test_on_requires_root(self):
        m.DRY_RUN = False
        try:
            with mock.patch.object(m, "is_root", return_value=False):
                with self.assertRaises(m.Error):
                    m.turn_on()
        finally:
            m.DRY_RUN = False


if __name__ == "__main__":
    unittest.main()
