Encode Workflow Operation Handler
=================================

> Parallel FFmpeg encoding

Description
-----------

The encode workflow operation can be used to encode media files to different formats using [FFmpeg](https://ffmpeg.org).

Its functionality is similar to the [compose workflow operation](compose-woh.md) but can utilize the parallel encoding
capabilities of FFmpeg. This has the advantage that the source file needs to be read only once for several encodings,
reducing the encoding time quite a lot. Additionally, this will let FFmpeg make better use of multiple CPU cores.


## Parameter Table

|configuration keys|example           |description                           |
|------------------|------------------|--------------------------------------|
|source-flavor     |presenter/work    |Which media should be encoded         |
|target-flavor     |presenter/delivery|Specifies the flavor of the new media |
|source-tags       |sometag           |Tags of media to encode               |
|target-tags       |sometag           |Specifies the tags of the new media   |
|encoding-profile  |webm-hd           |Specifies the encoding profile to use |

As explained in the "Encoding Profile" section, every media file created by an encode operation has its own named
suffix. The suffix name is defined in the encode profile definition. It will be added as a tag to the corresponding
track in the media package. This is different from the `target-tags` workflow operation parameter, which will cause the
specified tag list to be added to every media file created by the operation.

For instance, let us take the example operation and encoding profile defined in this documentation. After a successful
run of the operation, the media package will contain four new tracks: the first one containing the new tags
`engage-download`, `engage-streaming` and `low-quality`; the second one containing the new tags `engage-download`,
`engage-streaming` and `medium-quality`; etc.

Operation Example
-----------------

    <operation
      id="encode"
      fail-on-error="true"
      exception-handler-workflow="error"
      description="encoding media files">
        <configurations>
        <configuration key="source-flavor">*/trimmed</configuration>
        <configuration key="target-flavor">*/delivery</configuration>
        <configuration key="target-tags">engage-download,engage-streaming</configuration>
        <configuration key="encoding-profile">parallel.http</configuration>
      </configurations>
    </operation>


Encoding Profile Example
------------------------

Unlike a regular compose operation, this operation can generate more than one output file and, therefore, more than one
media package track elements. In order to distinguish these tracks, the encoding profile syntax for this operation
allows different named suffix parameters in the form of `<profile_name>.suffix.<suffix_name> = <suffix_value>`.

Because file names are irrelevant for the workflow operations, each suffix name is added as a tag to the corresponding
media package element. For instance, if a media file with a filename of `myfile.ext` is processed with the encoding
profile in the example below, the first output file will be `myfile-low.mp4` and the resulting media package element
will contain a tag with the value `low-quality`; the second output file will be `myfile-medium.mp4` and the resulting
media package element will contain a tag with the value `medium-quality`; and so on.

    # Distribution format definition for low quality presenter download
    profile.parallel.http.name = parallel video encoding
    profile.parallel.http.input = visual
    profile.parallel.http.output = visual
    profile.parallel.http.suffix.low-quality = -low.mp4
    profile.parallel.http.suffix.medium-quality = -medium.mp4
    profile.parallel.http.suffix.high-quality = -high.mp4
    profile.parallel.http.suffix.hd-quality = -hd.mp4
    profile.parallel.http.ffmpeg.command = -i #{in.video.path} \
      -c:v libx264 -filter:v yadif,scale=-2:288 -preset slower -crf 28 -r 25 -profile:v baseline -tune film -movflags faststart \
      -c:a aac -ar 22050 -ac 1 -ab 32k #{out.dir}/#{out.name}#{out.suffix.low-quality} \
      -c:v libx264 -filter:v yadif,scale=-2:360 -preset slower -crf 25 -r 25 -profile:v baseline -tune film -movflags faststart \
      -c:a aac -ar 22050 -ac 1 -ab 48k #{out.dir}/#{out.name}#{out.suffix.medium-quality} \
      -c:v libx264 -filter:v yadif,scale=-2:576 -preset medium -crf 23 -r 25 -pix_fmt yuv420p -tune film  -movflags faststart \
      -c:a aac -ar 44100 -ab 96k #{out.dir}/#{out.name}#{out.suffix.high-quality} \
      -c:v libx264 -filter:v yadif,scale=-2:720 -preset medium -crf 23 -r 25 -pix_fmt yuv420p -tune film  -movflags faststart \
      -c:a aac -ar 44100 -ab 96k #{out.dir}/#{out.name}#{out.suffix.hd-quality}
