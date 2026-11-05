import sys
import pathlib
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from aluo import Session, wake, intent, cities

class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.output, self.requests = [], []
        self.now = 100
        self.s = Session(self.output.append, self.query, lambda: self.now)
    def query(self, target, catalog):
        self.requests.append(target)
        return '测试天气'
    def command(self):
        self.s.accept('你好阿洛')
        self.s.accept('北京明天的天气怎么样')
    def test_confirmation_required(self):
        self.command()
        self.assertEqual(self.requests, [])
        self.s.accept('是的')
        self.assertEqual(self.requests, [('北京',1)])
        self.assertEqual(self.s.state, 'WAKE')
    def test_cancel_and_timeout(self):
        self.command(); self.s.accept('取消')
        self.assertEqual(self.requests, [])
        self.command(); self.now += 9; self.s.accept('是的')
        self.assertEqual(self.requests, [])
    def test_unknown_consent_and_cooldown(self):
        self.command(); self.s.accept('不是不可以')
        self.assertEqual(self.requests, [])
        self.s.accept('是的'); self.command(); self.s.accept('是的')
        self.assertEqual(len(self.requests), 1)
    def test_no_substring_wake(self):
        for text in ['阿洛','你好阿洛北京天气怎么样','他说你好阿洛','你好阿波','你好阿罗']:
            self.assertFalse(wake(text))
        self.assertTrue(wake('你好，阿洛！'))
    def test_ambiguous_slots(self):
        for text in ['天气怎么样','北京上海天气','北京今天明天天气','北京后天天气','不要查询北京天气']:
            self.assertIsNone(intent(text,cities()))
    def test_query_failure_resets(self):
        self.s.query = lambda *_: (_ for _ in ()).throw(OSError())
        self.command(); self.s.accept('是的')
        self.assertEqual(self.s.state, 'WAKE')
        self.assertIn('失败', self.output[-1])

if __name__ == '__main__': unittest.main()
