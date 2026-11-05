import sys,pathlib,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from autostart import inspect_graph,CARD,SOURCE,SINK,PROFILE
class StartupTests(unittest.TestCase):
    def graph(self,current=True):
        return [{'id':901,'type':'PipeWire:Interface:Device','info':{'props':{'device.name':CARD},'params':{'EnumProfile':[{'index':123,'name':PROFILE}],'Profile':[{'name':PROFILE if current else 'a2dp-sink'}]}}}]+[{'type':'PipeWire:Interface:Node','info':{'props':{'node.name':n}}} for n in [SOURCE,SINK]]
    def test_dynamic_ids(self):
        self.assertEqual(inspect_graph(self.graph()),dict(id=901,index=123,current=True,ready=True))
    def test_no_headset_or_missing_sink(self):
        self.assertIsNone(inspect_graph([]))
        self.assertFalse(inspect_graph(self.graph()[:-1])['ready'])
    def test_playback_only_cannot_start(self):
        self.assertFalse(inspect_graph(self.graph(False))['ready'])
    def test_other_headset_cannot_start(self):
        graph=self.graph();graph[0]['info']['props']['device.name']='other-headset'
        self.assertIsNone(inspect_graph(graph))
if __name__=='__main__':unittest.main()
