#  NTS MK - an NTS Radio app for the Mudita Kompakt e-ink phone
A custom minimalist app designed for the Mudita Kompakt e-ink phone to let users listen to NTS Radio's live channels and infinite mixtapes.
#  Screenshots
![screenshot of app.](https://github.com/edknight490/NTSMK/blob/main/Screenshot-1.jpeg) ![screenshot of app.](https://github.com/edknight490/NTSMK/blob/main/Screenshot-2.jpeg) ![screenshot of app.](https://github.com/edknight490/NTSMK/blob/main/Screenshot-3.jpeg) ![screenshot of app.](https://github.com/edknight490/NTSMK/blob/main/Screenshot-4.jpeg)
# About the app
This app lets you stream both NTS Radio live channels, as well as its 16 infinite mixtapes - non-stop, ad-free playlists, each with a different theme, genre, or vibe. It's deliberately a minimal design, and does not let the user access any of the features only available to paid subscribers, such as live tracklists or the ability to save songs. This app was made using Google AI Studio. As much as I dislike AI, the existing NTS app doesn't work well with the Mudita Kompakt and it's such a niche use, anyone with coding ability was unlikely to make one.
# Navigation and controls
The live show cards will display the current show's title and the show description. If the show's description is too long to fit in and is cut off, pressing down on the box will expand it to take up the whole screen and hopefully display all the text.
To switch between live channels or infinite mixtapes, you can either press one of the two buttons at the top of the screen, or swipe to the side in the area between the pagination dots and the player control area at the bottom of the app.
Swiping left or right on the live show cards will switch between these cards (but not change the channel playing), and swiping on the infinite mixtape cards will scroll through the available infinite mixtapes.
To start playing one of the live shows or mixtapes, either tap the play icon on its card, or double tap the card itself. Doing so on the show that is playing will pause the show.
As seen in the screenshots, you can also use the home screen player controls. If playing a live show, the next/previous track buttons will switch between the two live channels, and if playing one of the infinite mixtapes it'll switch between each of the mixtapes. Please note that this app has been tested using the third party Katapult launcher so it's possible there are issues with other launchers.
At the bottom of the screen, the volume slider will adjust the phone's media volume, and the play button will start and pause a stream.
The pause button on the home screen controls will pause the stream, and the stop button will hard close the app.
# Known limitations
NTS Radio's streams are all 256kbps MP3. There is no variable bitrate, so if your connection can't keep up it'll buffer instead of adjusting the bitrate.
Each live show starts on the hour every hour, but the API used to access the show information only seems to make an updated version available at around 12/13 mninutes past each hour. I've set the app to fetch new information around then, as well as whenever you start playing a show. You can also drag down on one of the live show card boxes to manually refresh.

