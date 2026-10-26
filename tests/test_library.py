from ftp_player.core.models import Account, MediaFile
from ftp_player.core.library import Library, sort_entries
from ftp_player.core.remote import index_metadata
from ftp_player.services.thumbnails import ThumbnailCache
from PIL import Image


def test_folder_first_natural_sort_unknown_metadata_last():
    rows = [MediaFile('film10.mov','/10',20,modified=200,metadata_known=True),
            MediaFile('folder','/folder',directory=True), MediaFile('film2.mkv','/2',10,modified=100,metadata_known=True),
            MediaFile('unknown.avi','/unknown')]
    assert [e.name for e in sort_entries(rows)] == ['folder','film2.mkv','film10.mov','unknown.avi']
    assert [e.name for e in sort_entries(rows,'size',True)] == ['folder','film10.mov','film2.mkv','unknown.avi']
    assert sort_entries(rows,'modified',True)[1].modified == 200
    assert sort_entries(rows,'ext')[1].ext == 'avi'


def test_per_server_bookmarks_and_startup(tmp_path):
    account=Account('same','A','https://one/media/')
    other=Account('same','A','https://two/media/')
    library=Library(tmp_path)
    library.visited(account,'/last')
    assert library.startup(account) == '/last'
    library.set_start(account,'/start')
    assert library.startup(account) == '/start'
    assert library.startup(other) == '/'
    library.toggle_folder(account,'/folder')
    entry=MediaFile('movie.mov','/movie.mov',100,account_id='same',url='http://127.0.0.1/secret')
    library.toggle_file(account,entry)
    loaded=Library(tmp_path)
    assert loaded.state(account)['folders'] == ['/folder']
    assert loaded.favorites(account)[0].path == '/movie.mov'
    assert '127.0.0.1' not in loaded.path.read_text()
    loaded.toggle_file(account,entry)
    assert loaded.favorites(account) == []
    loaded.set_start(account,'')
    assert loaded.startup(account) == '/last'


def test_apache_and_nginx_metadata():
    size,date,known=index_metadata(' 2026-10-25 12:34 2048 ')
    assert size == 2048 and date > 0 and known
    size,date,known=index_metadata(' 25-Oct-2026 12:34 1.5M ')
    assert size == 1572864 and date > 0 and known
    assert index_metadata('no metadata') == (0,0,False)
    from ftp_player.core.remote import list_timestamp
    assert list_timestamp('-rw-r--r-- 1 owner group 123 Oct 25 2026 movie.mov') == '20261025000000'
    assert list_timestamp('10-25-26  12:34PM  123 movie.mov') == '20261025123400'


def test_thumbnail_cache_signature_and_reuse(tmp_path):
    cache=ThumbnailCache(tmp_path)
    account=Account('a','A','https://server/')
    entry=MediaFile('movie.mov','/movie.mov',100,modified=100)
    key=cache.key(account,entry)
    for index in range(3):
        Image.new('RGB',(320,180),(index*80,20,30)).save(tmp_path/f'{key}-{index}.jpg')
    assert len(cache.cached(account,entry)) == 3
    # Cache hit must not open a stream or invoke a decoder.
    assert len(cache.create(account,entry,'invalid://must-not-open')) == 3
    entry.modified=101
    assert not cache.cached(account,entry)
    assert cache.key(account,entry) != key
